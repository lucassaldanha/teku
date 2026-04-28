# Gossipsub Partial Messages — Teku Implementation Design

Status: **Draft / MVP design**
Tracking issue: TBD
Last updated: see `git log -- docs/partial-messages.md`

This document is the source of truth for the Teku side of the gossipsub
partial-messages extension as applied to PeerDAS cell dissemination. It
captures scope, the Teku ↔ jvm-libp2p responsibility boundary, the spec-derived
SSZ types we need to add, the validation/eager-push/reassembly semantics, and
an independently-mergeable implementation plan.

It is a sibling to the jvm-libp2p design doc
(`jvm-libp2p:docs/partial-messages.md`) and assumes the API surface defined
there (`Gossip.publishPartial`, `PartialMessagesHandler<PeerState>`,
`PublishActionsFn<PeerState>`, `PublishAction`, `PartialMessagesPeerFeedback`).
This is a **living document** — append to the decision log (§15) when we
revise anything.

---

## 1. Scope and non-goals

### In scope (MVP)

- Teku can act as a partial-messages peer on `data_column_sidecar_{subnet_id}`
  topics, end-to-end with another partial-capable client (Prysm, Lighthouse,
  go-libp2p reference).
- New SSZ containers from consensus-specs PR #4558 (Fulu):
  - `PartialDataColumnSidecar`
  - `PartialDataColumnPartsMetadata` (`available` + `requests` bitlists)
  - `PartialDataColumnHeader` (Fulu variant: includes `signed_block_header` and
    `kzg_commitments_inclusion_proof`).
- Per-`(topic, blockRoot)` peer-state tracking: which cells we have, which
  cells the peer has, which cells the peer requested from us.
- Two-stage validation pipeline (header first, then cells) plumbed through
  `PartialMessagesHandler.onIncomingRpc`.
- Eager pushing of `PartialDataColumnHeader` to peers that haven't previously
  sent us a message; opt-in CLI flag for eager-pushing all cells when
  proposing.
- Cell-level reconstruction into a full `DataColumnSidecar` and forwarding
  over standard gossipsub to non-partial peers (backwards compatibility).
- Hooked into `DataColumnSidecarManager` so reconstructed sidecars feed the
  existing custody / sampling / recovery paths unchanged.
- Scoring: first-message-delivery rewards on novel cells (rate-limited),
  invalid-message-delivery on KZG/inclusion failures, via
  `PartialMessagesPeerFeedback`.
- CLI flag (default `false`) gates the entire feature in Teku.

### Non-goals (deferred, but documented for future)

- Gloas variant (`PartialDataColumnHeader` with `slot` + `beacon_block_root`
  instead of `signed_block_header` + inclusion proof). Stubbed at the SpecLogic
  level using the existing fork-aware util pattern, but no end-to-end Gloas
  test in MVP. Tracked in §15.
- Partials for any other topic (blocks, blobs, attestations). Spec only
  defines partials for column sidecars.
- Eager-push policy beyond "header to peers who haven't messaged us yet" plus
  the proposer opt-in flag. Adaptive eager-push based on peer scoring is
  future work.
- Re-entering reconstructed full `DataColumnSidecar` into our own ingestion
  twice (we deliver the reassembled sidecar to `DataColumnSidecarManager`
  exactly once; `LibP2PGossipNetwork.gossip(...)` to non-partial peers is a
  separate publish path).
- Reuse of partial-message infrastructure for other PeerDAS optimisations
  (e.g. private-blob fast paths beyond the proposer opt-in).
- `interop-test-client` integration. Deferred until the libp2p test-plan
  catches up; we'll lean on Prysm/Lighthouse interop instead.

---

## 2. Reference pins

The partial-messages spec is **lifecycle 1A (working draft)** at both the
libp2p and consensus-spec layers. Pins below should be updated whenever this
document is revised.

| Source | Pin | Location |
|---|---|---|
| libp2p/specs partial-messages | merge `6b6203ee` (PR #685, merged 2026-02-26) | `pubsub/gossipsub/partial-messages.md` |
| ethereum/consensus-specs cell dissemination | PR [#4558](https://github.com/ethereum/consensus-specs/pull/4558) (`cell-dissemination` branch) | `specs/fulu/p2p-interface.md`, `specs/gloas/p2p-interface.md` |
| jvm-libp2p partial messages | branch `feature/partial-messages-support` (HEAD `49143445`, 2026-04-24) | see `jvm-libp2p:docs/partial-messages.md` |
| OffchainLabs/prysm | branch `prysm/partial-cells-current` | `beacon-chain/p2p/partialdatacolumnbroadcaster/`, `consensus-types/blocks/partialdatacolumn.go`, `proto/prysm/v1alpha1/partial_data_columns.proto` |
| sigp/lighthouse | PR [#8314](https://github.com/sigp/lighthouse/pull/8314) | n/a |

### Related in-flight spec work (watch but not blocking)

- libp2p/specs#681 — Choke extension.
- libp2p/specs#699 — Topic table.
- libp2p/specs#706 — Gossipsub v1.4.

---

## 3. Architecture overview

### Where it lives in Teku

```
networking/eth2/                     <-- new orchestration code
  gossip/
    DataColumnSidecarGossipManager       (existing — keeps the full-message path)
    partialmessages/                     <-- new
      PartialDataColumnSidecarHandler        implements PartialMessagesHandler<PeerState>
      PartialDataColumnSidecarPublisher      drives publishPartial() per (topic, group)
      PartialDataColumnGroupKey              (forkDigest, subnetId, blockRoot)
      PartialDataColumnPeerState             (per-peer view inside a group)
      PartialDataColumnHeaderCache           shared across subnets for a block
      PartialDataColumnFeedbackBridge        adapter to peer-scoring
networking/eth2/.../subnets/
    DataColumnSidecarSubnetSubscriptions     (existing — extended to subscribe with
                                              requestsPartial=true / supportsSendingPartial=true)

networking/p2p/.../libp2p/gossip/
  LibP2PGossipNetwork                       (extended with publishPartial passthrough +
                                              partialMessagesHandler wiring)
  LibP2PGossipNetworkBuilder                (wires the handler into GossipRouterBuilder)

ethereum/spec/.../datastructures/blobs/
  PartialDataColumnSidecar*                  (new SSZ containers, Fulu schema)
  PartialDataColumnPartsMetadata*            (new SSZ containers, Fulu schema)
  PartialDataColumnHeader*                   (new SSZ containers, Fulu/Gloas variants)

ethereum/spec/.../logic/common/util/
  PartialDataColumnSidecarUtil               (abstract base, mirrors the
                                              DataColumnSidecarUtil hierarchy
                                              described in CLAUDE.md)
ethereum/spec/.../logic/versions/fulu/util/
  PartialDataColumnSidecarUtilFulu           (header inclusion proof, KZG batch
                                              verify on bitmap-selected cells)
ethereum/spec/.../logic/versions/gloas/util/
  PartialDataColumnSidecarUtilGloas          (Gloas header variant; deferred)

ethereum/statetransition/.../validation/
  PartialDataColumnHeaderGossipValidator     (header validation rules, §8.1)
  PartialDataColumnSidecarGossipValidator    (cell validation rules, §8.2)

ethereum/statetransition/.../datacolumns/
  DataColumnSidecarManager                  (extended: ingest a sidecar reassembled
                                              from partial cells, identical path
                                              after that)
```

### Data flow

```
        ┌────────────────────────────────────────────────────────────────┐
        │                       jvm-libp2p (Gossip)                       │
        │                                                                 │
        │   inbound RPC.PartialMessagesExtension                          │
        │     └─> PartialMessagesAdapter.onIncomingRpc                    │
        │           └─> PartialDataColumnSidecarHandler.onIncomingRpc ────┤
        │   outbound (Teku-driven) Gossip.publishPartial(topic, group, fn)│
        │     ^                                                           │
        │     └── PartialDataColumnSidecarPublisher                       │
        └─────┬───────────────────────────────────────────────────────────┘
              │
              ▼
   ┌───────────────────────────────┐
   │ PartialDataColumnSidecar       │  (parse SSZ, dispatch to async runner)
   │ Handler                        │
   └─────────┬─────────────────────┘
             │ (async)
             ├──> PartialDataColumnHeaderGossipValidator    (§8.1)
             ├──> PartialDataColumnSidecarGossipValidator   (§8.2, KZG)
             ├──> update peer-state map (cells available, requests)
             ├──> on novel valid cells:
             │      reportFeedback(USEFUL)
             │      append to local cell store for (blockRoot, columnIndex)
             │      if reconstruct possible:
             │        build full DataColumnSidecar
             │        deliver to DataColumnSidecarManager (existing flow)
             │        gossip.gossip(topic, encoded_full_sidecar)   <-- to non-partial peers
             │        (the suppression rule in jvm-libp2p §5.1
             │         drops it for partial-requesting peers)
             ├──> on invalid:
             │      reportFeedback(INVALID)
             │      drop
             └──> on duplicate:
                    reportFeedback(IGNORED)
                    drop
```

The right-hand side (header cache, cell store, reconstruction) is owned by
Teku. The left-hand side (per-peer-per-group state container, TTL GC, routing,
wire framing) is owned by jvm-libp2p.

---

## 4. Spec-derived data structures

All three are new SSZ containers, defined in consensus-specs PR #4558. They
live under `ethereum/spec/src/main/java/tech/pegasys/teku/spec/datastructures/blobs/`,
alongside `DataColumnSidecar`, with schema providers wired into
`SchemaDefinitionsFulu` (and a Gloas-variant header into `SchemaDefinitionsGloas`).

### 4.1 `PartialDataColumnSidecar` (Fulu)

```python
class PartialDataColumnSidecar(Container):
    cells_present_bitmap: Bitlist[MAX_BLOB_COMMITMENTS_PER_BLOCK]
    partial_column:       List[Cell, MAX_BLOB_COMMITMENTS_PER_BLOCK]
    kzg_proofs:           List[KZGProof, MAX_BLOB_COMMITMENTS_PER_BLOCK]
    header:               List[PartialDataColumnHeader, 1]   # optional, eager-push only
```

Notes:
- The column index is **not** in the body — it is implied by the topic
  (`data_column_sidecar_{subnet_id}`). A given partial RPC therefore carries
  cells from possibly-many blobs, all at the same column index.
- `len(partial_column) == len(kzg_proofs) == popcount(cells_present_bitmap)`
  is a wire-level invariant the validator enforces.
- The optional `header` field is exactly the `PartialDataColumnHeader` and is
  carried only in messages that the sender chooses to eagerly push (see §9).

### 4.2 `PartialDataColumnPartsMetadata`

```python
class PartialDataColumnPartsMetadata(Container):
    available: Bitlist[MAX_BLOB_COMMITMENTS_PER_BLOCK]
    requests:  Bitlist[MAX_BLOB_COMMITMENTS_PER_BLOCK]
```

Two bitmaps of equal length, one bit per blob index (the part index is the blob
index for the column implied by the topic). Two bits of state per cell:

| `available` | `requests` | Meaning |
|---|---|---|
| 0 | 0 | Peer doesn't have it and doesn't want it |
| 0 | 1 | Peer doesn't have it and wants it |
| 1 | * | Peer has it and is willing to provide it |

This is what the `partsMetadata` field of `Rpc.PartialMessagesExtension`
carries on the wire — opaque bytes to jvm-libp2p, SSZ-decoded by Teku inside
`onIncomingRpc`.

### 4.3 `PartialDataColumnHeader` (Fulu)

```python
class PartialDataColumnHeader(Container):
    kzg_commitments:                  List[KZGCommitment, MAX_BLOB_COMMITMENTS_PER_BLOCK]
    signed_block_header:              SignedBeaconBlockHeader
    kzg_commitments_inclusion_proof:  Vector[Bytes32, KZG_COMMITMENTS_INCLUSION_PROOF_DEPTH]
```

The header is shared across **all** column subnets for a given block. Once
validated on any subnet, it's reusable for every subnet for that block — see
§8.1.

### 4.4 Gloas variant (deferred)

```python
class PartialDataColumnHeader(Container):
    kzg_commitments:     List[KZGCommitment, MAX_BLOB_COMMITMENTS_PER_BLOCK]
    slot:                Slot
    beacon_block_root:   Root
```

In Gloas the header validates against the execution-payload-bid path rather
than the beacon-block path; we mirror this with a fork-specific
`PartialDataColumnSidecarUtilGloas` (see CLAUDE.md → "Fork-Aware Development
Patterns") but ship Fulu only for MVP.

---

## 5. Responsibility boundary recap (Teku ↔ jvm-libp2p)

The library has the generic boundary documented in
`jvm-libp2p:docs/partial-messages.md §3`. The table below makes it concrete
for cell dissemination.

| Concern | jvm-libp2p | Teku |
|---|---|---|
| Subscribing topics with `requestsPartial = true` / `supportsSendingPartial = true` | API only | ✅ (`DataColumnSidecarSubnetSubscriptions`) |
| Wire framing of `Rpc.PartialMessagesExtension` | ✅ | — |
| Per-peer partial-capability state, per-`(topic, groupId)` group state, TTL GC, DoS caps | ✅ | — |
| Routing: full-message suppression / IDONTWANT suppression / IHAVE replacement | ✅ | — |
| Group ID generation (`0x00 ‖ block_root`) | ❌ opaque bytes | ✅ |
| `partsMetadata` SSZ encoding/decoding (`PartialDataColumnPartsMetadata`) | ❌ opaque bytes | ✅ |
| `partialMessage` SSZ encoding/decoding (`PartialDataColumnSidecar`) | ❌ opaque bytes | ✅ |
| Header cache shared across column subnets for a given block | — | ✅ |
| Header validation (proposer signature, parent, finalized, inclusion proof) | — | ✅ (`PartialDataColumnHeaderGossipValidator`) |
| Cell validation (KZG batch verify on bitmap-selected cells) | — | ✅ (`PartialDataColumnSidecarGossipValidator`) |
| Deciding which cells to send to which peer | — | ✅ (`PartialDataColumnSidecarPublisher.PublishActionsFn`) |
| Reconstructing full `DataColumnSidecar` once enough cells arrive | — | ✅ |
| Forwarding reconstructed full sidecar to non-partial peers | — | ✅ |
| Per-cell peer scoring (first-message-delivery, invalid) | — | ✅ via `PartialMessagesPeerFeedback` |
| Eager-push policy (header to fresh peers; opt-in proposer cells) | — | ✅ |

---

## 6. Per-(topic, group) state

### 6.1 Group identity

```
groupId :=  0x00 || blockRoot                  # 33 bytes
group   :=  (topic, groupId)
            ≡ (forkDigest, subnetId, blockRoot)
```

Group identity is per-block-per-column: each `data_column_sidecar_{subnet_id}`
topic carries one group per block root. The version byte is fixed `0x00` per
spec; we reject groups with a different prefix.

### 6.2 What jvm-libp2p stores for us

The library stores `Map<PeerId, PeerState>` per group. We pick the `PeerState`
type:

```java
record PartialDataColumnPeerState(
    PartialDataColumnPartsMetadata lastSeenMetadata,   // peer's view as of last RPC
    BitSet cellsSentToPeer,                            // cells *we* have shipped to this peer
    BitSet cellsRequestedByPeer,                       // cells the peer has asked us for
    Optional<UInt64> lastFirstDeliveryRewardTimeMillis // for FMD rate-limit
) {}
```

`PeerState` is opaque to the library; the library only stores it,
unconditionally returns the previous value when handing the map to our
callbacks, and atomically replaces it via `PublishAction.nextPeerState`.

### 6.3 What Teku owns out-of-band

Two caches Teku owns directly (not in `PeerState`, because they are
shared-across-peers):

- `PartialDataColumnHeaderCache: Map<Bytes32, ValidatedPartialDataColumnHeader>`
  — keyed on `blockRoot`. A header validated for any subnet is reusable for
  every subnet for that block (spec §"Partial Messages on
  data_column_sidecar_{subnet_id}").
- `PartialDataColumnLocalCellStore: Map<(blockRoot, columnIndex), BitSet, List<Cell>, List<KZGProof>>`
  — what we know locally for each `(block, column)`. Drives both reconstruction
  and outbound publishing decisions. Sized-bounded by an LRU keyed on
  `blockRoot` (capacity ≈ `MIN_EPOCHS_FOR_DATA_COLUMN_SIDECARS_REQUESTS *
  SLOTS_PER_EPOCH`).

Both caches are populated and consumed by the handler. Cleanup on peer
disconnect / topic unsubscribe is handled by jvm-libp2p (§6.4 of the
jvm-libp2p doc); Teku-owned caches use their own LRU/TTL.

---

## 7. Handler contract

A single `PartialDataColumnSidecarHandler` is registered on the
`GossipRouterBuilder`:

```java
class PartialDataColumnSidecarHandler
        implements PartialMessagesHandler<PartialDataColumnPeerState> {

    void onIncomingRpc(
        PeerId from,
        Map<PeerId, PartialDataColumnPeerState> peerStates,
        Rpc.PartialMessagesExtension rpc,
        PartialMessagesPeerFeedback feedback) {
        // 1. Decode groupId -> (versionByte, blockRoot). Reject if versionByte != 0.
        // 2. SSZ-decode partsMetadata (if present) + partialMessage (if present).
        // 3. Hand off to async runner — must not block the pubsub event thread.
        //    Inside the async block:
        //      a. If body has header: validate against header rules (§8.1).
        //         Cache validated header in PartialDataColumnHeaderCache.
        //      b. If body has cells: require validated header for blockRoot.
        //         Validate KZG (§8.2). On INVALID: feedback.report(INVALID).
        //         On novel: feedback.report(USEFUL) (rate-limited),
        //                   merge into PartialDataColumnLocalCellStore.
        //         On dup:   feedback.report(IGNORED).
        //      c. Update peer-state: lastSeenMetadata <- metadata,
        //         cellsRequestedByPeer |= metadata.requests where !available.
        //      d. If reconstruction now possible: reconstruct, deliver to
        //         DataColumnSidecarManager, and re-gossip full sidecar.
        //      e. If we now have anything the peer requested-but-doesn't-have:
        //         schedule a publishPartial(...) for this group.
    }

    void onEmitGossip(
        Topic topic,
        byte[] groupId,
        Collection<PeerId> gossipPeers,
        Map<PeerId, PartialDataColumnPeerState> peerStates,
        PartialMessagesPeerFeedback feedback) {
        // Heartbeat-driven. For each gossipPeer that hasn't gotten our latest
        // availability bitmap recently, schedule a publishPartial(...) that
        // sends partsMetadata-only (no payload).
    }
}
```

Both callbacks run on the pubsub event thread and must be fast and
non-blocking. All real work (decoding, KZG, signature checks, EL queries)
happens on Teku's existing async runner.

### 7.1 The publisher (`publishPartial`)

`PartialDataColumnSidecarPublisher` calls `Gossip.publishPartial(topic,
groupId, actionsFn)` whenever Teku has new state to share. It is the only
caller of that API; everything in §4–§6 funnels through it.

```java
PublishActionsFn<PartialDataColumnPeerState> actionsFn =
    (peerStates, peerRequestsPartial) -> Sequence.ofPeerActions(
        peerStates.entrySet().stream().flatMap(entry -> {
            // 1. Compute what cells this peer wants and we have.
            // 2. Apply eager-push policy (§9) for whether to attach a header.
            // 3. Build PartialDataColumnSidecar (cells + proofs + optional header).
            // 4. Build PartialDataColumnPartsMetadata (our availability + our
            //    requests for what *we* still need).
            // 5. Compute nextPeerState — increment cellsSentToPeer.
            return Stream.of(Pair.of(entry.getKey(), new PublishAction<>(
                /* partialMessage */ sszEncode(partial),
                /* partsMetadata  */ sszEncode(metadata),
                /* nextPeerState  */ next,
                /* error          */ null)));
        }));
```

Three calling sites:

1. **Inbound completion**: after `onIncomingRpc` ingests new cells, if any
   peer now has an unmet `requests` bit we can satisfy.
2. **Heartbeat tick** (`onEmitGossip`): metadata-only republish to keep peers'
   views fresh.
3. **Local origination**: when our own custody/sampling finds new cells (e.g.
   recovered via `DataColumnSidecarRecoveringCustody`) we publish them out.
4. **Proposer eager-push** (opt-in): when we propose a block, push cells of
   private blobs unconditionally to mesh peers.

---

## 8. Validation

The spec validates partial RPCs in two stages. We mirror this with two
validator classes; both run on the async runner inside `onIncomingRpc`.

### 8.1 `PartialDataColumnHeaderGossipValidator` (Fulu rules)

For every partial message that contains a header:

- **REJECT** A header and/or cells are present (not semantically empty).
- **REJECT** `len(partial_column) == len(kzg_proofs) == popcount(cells_present_bitmap)`.
- **REJECT** If a previously-valid header for `blockRoot` exists in cache, the
  received header MUST equal it.
- **REJECT** `hash_tree_root(signed_block_header.message) == blockRoot` (= the
  group ID stripped of its version byte).
- **REJECT** `kzg_commitments` non-empty.
- **IGNORE** `block_header.slot <= current_slot + MAXIMUM_GOSSIP_CLOCK_DISPARITY`.
- **IGNORE** `block_header.slot > finalized_slot`.
- **REJECT** Proposer signature valid w.r.t. `proposer_index` pubkey.
- **IGNORE** Parent block (`block_header.parent_root`) is known.
- **REJECT** Parent block passes validation.
- **REJECT** `block_header.slot > parent.slot`.
- **REJECT** `finalized_checkpoint` is an ancestor of the header's block.
- **REJECT** `verify_partial_data_column_header_inclusion_proof(header)`.
- **REJECT** Proposer is the expected proposer for the slot/parent
  shuffling. **IGNORE** if shuffling unavailable.

Most of these reuse helpers already in
`statetransition/.../validation/GossipValidationHelper.java` and
`DataColumnSidecarGossipValidator.java` — explicitly factor out the shared
helpers rather than copy/paste.

On success: cache `(blockRoot, header)` in `PartialDataColumnHeaderCache`.

### 8.2 `PartialDataColumnSidecarGossipValidator` (cells)

For every partial message that contains cells:

- **IGNORE** if no header is cached for `blockRoot` (when the body has cells
  only).
- **IGNORE** Header slot rules same as §8.1 (re-checked against current
  finalized state to handle late delivery).
- **REJECT** `len(cells_present_bitmap) == len(header.kzg_commitments)`.
- **(Optional) REJECT** For cells we already have: bytes equal to local copy.
  We implement this only if the local cell is already in
  `PartialDataColumnLocalCellStore`; otherwise skip the check.
- **REJECT** `verify_partial_data_column_sidecar_kzg_proofs(sidecar,
  header.kzg_commitments, columnIndex)` — KZG batch verify.

The column index is derived from the topic via the existing
`DataColumnSidecarSubnetTopicProvider` ↔ `MiscHelpersFulu` mapping.

### 8.3 Verification helpers

Two new helpers on `MiscHelpersFulu` (added in §4 of the implementation plan):

- `verifyPartialDataColumnHeaderInclusionProof(PartialDataColumnHeader)`
- `verifyPartialDataColumnSidecarKzgProofs(PartialDataColumnSidecar,
  List<KZGCommitment>, columnIndex)`

Both delegate to the same KZG / Merkle libraries as the existing full-sidecar
helpers; the only difference is operating over the bitmap-selected subsets.

---

## 9. Eager pushing

Three layers of policy, in increasing aggressiveness, all in the publisher:

### 9.1 Header eager-push (default ON)

When we send any partial RPC to a peer, attach the header iff:
- We have a validated header for `blockRoot` in cache, **and**
- The peer has not previously sent us **any** partial RPC for this group
  (tracked via `PartialDataColumnPeerState.lastSeenMetadata == null`).

Rationale: per spec, "Clients SHOULD NOT send a `PartialDataColumnHeader` to
a peer that has already sent the client a message, as that peer already has
the header (it is a prerequisite to sending a message)."

### 9.2 Cell eager-push for private blobs (opt-in, off by default)

CLI flag: `--Xpartial-data-column-eager-push-when-proposing` (default `false`).
When enabled and we are proposing a block:
- For every column subnet we publish to (existing path), we additionally call
  `publishPartial(topic, groupId, ...)` with the cells of all blobs whose
  `KZGCommitment` was not present in any externally-observed gossip in the
  preceding slot — i.e. private/builder-blinded blobs.
- Targeted at our mesh peers + fanout, mirroring the standard
  `Gossip.gossip(...)` path.

This matches the spec language: "a proposer including private blobs SHOULD
eagerly push the cells corresponding to the private blobs".

### 9.3 Reactive partial publish (always on, when feature enabled)

On every `onIncomingRpc` and on every heartbeat tick (`onEmitGossip`), if any
peer has unmet requests we can serve, schedule a `publishPartial(...)` to that
peer with the requested cells (and updated metadata).

---

## 10. Reassembly and forwarding

### 10.1 Reassembly trigger

After every successful cell ingest into `PartialDataColumnLocalCellStore`,
check whether we now have all cells for `(blockRoot, columnIndex)`:

- We need a validated header for `blockRoot` and a complete
  `cells_present_bitmap` of length `len(header.kzg_commitments)`.
- When complete, materialise a full `DataColumnSidecar` via
  `DataColumnSidecarBuilder` (existing class) and ship it both to:
  1. `DataColumnSidecarManager.onSidecar(sidecar)` — the existing ingest
     path; everything downstream (custody, sampling, recovery,
     `ValidDataColumnSidecarsListener`) is unchanged.
  2. `LibP2PGossipNetwork.gossip(topic, encoded)` — standard gossip republish,
     which thanks to jvm-libp2p §5.1 routing rule will be filtered out for
     partial-requesting peers and delivered only to non-partial peers.

This means the rest of Teku is **unaware** of partial messages: it sees full
`DataColumnSidecar`s arriving from the network exactly as today.

### 10.2 Idempotence

The standard gossip republish in §10.1.2 will re-enter our own
`DataColumnSidecarTopicHandler` via the libp2p loopback. The existing
seen-cache (`TTLSeenCache` in `LibP2PGossipNetworkBuilder`) already
deduplicates by `messageSha256`, so this is harmless. Verified by an explicit
acceptance test (Step 9 of the plan).

---

## 11. Scoring

We don't implement custom partial-specific scoring rules; we feed back into
the existing peer scoring infrastructure via `PartialMessagesPeerFeedback`
(jvm-libp2p §4.4):

| Outcome | Feedback | Maps to (jvm-libp2p side) |
|---|---|---|
| Novel valid cell received | `USEFUL` | First message delivery score bump (rate-limited) |
| Invalid header / cell / KZG / inclusion proof | `INVALID` | `notifyRouterMisbehavior` → existing score penalty |
| Duplicate / already-seen cell | `IGNORED` | No-op |

Spec rate-limit: "Clients SHOULD limit the rate at which a peer gets the first
message delivery reward to prevent a peer from scoring better by providing
cells one at a time rather than many cells at once."

Implementation: `PartialDataColumnPeerState.lastFirstDeliveryRewardTimeMillis`
gates `USEFUL` at most once per `partialMessageFmdRateLimitMillis`
(default 250 ms — see §15 open questions).

---

## 12. Configuration and CLI

Single feature flag, gated experimental (`--X` prefix) until interop coverage
exists:

| Flag | Default | Description |
|---|---|---|
| `--Xpartial-data-column-sidecar-gossip-enabled` | `false` | Master switch. When `true`, subscribes to `data_column_sidecar_{subnet_id}` topics with `requestsPartial = true, supportsSendingPartial = true` and registers the partial-messages handler on the gossip router. |
| `--Xpartial-data-column-eager-push-when-proposing` | `false` | If `true`, eagerly push private-blob cells to mesh peers when proposing (§9.2). Ignored unless the master flag is on. |

When the master flag is `false`:
- We do not negotiate partial on `SubOpts`; behaviour is identical to today.
- The handler / publisher / caches are not constructed.
- The `GossipExtension.PARTIAL_MESSAGES` capability is **not** advertised.

When `true`:
- We still receive full-sidecar gossip from non-partial peers (the routing
  rules degrade gracefully). Reassembly delivers reconstructed sidecars to
  both the partial- and full-message paths transparently (§10).

Any additional knobs (FMD rate limit, header cache size, local cell store
capacity) are exposed only as `Network*Options` constants until we have
production data — explicitly **not** new CLI flags.

---

## 13. Testing strategy

Each step in §14 ships its own tests. The matrix that must be green by Step 9:

| Scenario | Where |
|---|---|
| SSZ round-trip for `PartialDataColumnSidecar`, `PartialDataColumnPartsMetadata`, `PartialDataColumnHeader` | `ethereum/spec` unit |
| Header validator: every REJECT/IGNORE rule from §8.1 | `ethereum/statetransition` unit (mirror `AbstractDataColumnSidecarGossipValidatorTest`) |
| Cell validator: every REJECT rule from §8.2 (incl. KZG batch failure) | as above |
| Handler: ingest header-only, cells-only, header+cells, all four combos | `networking/eth2` unit, with a fake `PartialMessagesPeerFeedback` |
| Publisher: omit `partialMessage` when peer supports-but-didn't-request | as above |
| Reassembly: full sidecar reaches `DataColumnSidecarManager` exactly once | `networking/eth2` integration |
| Forwarding: reassembled sidecar reaches non-partial peers, suppressed for partial peers | acceptance test, two-node libp2p simulation |
| Eager-push proposer flag: private blobs cells reach mesh on block publish | acceptance test |
| Master flag off: byte-for-byte identical to today's gossip | regression: existing data-column-sidecar gossip tests must remain green |
| Interop with Prysm `prysm/partial-cells-current` | manual interop, then promoted to acceptance test once libp2p test-plan supports it |

Reuse the `Abstract...Test` fork-aware pattern from CLAUDE.md so adding the
Gloas variant later is mechanical.

---

## 14. Implementation plan

Order chosen so each step is testable in isolation, the master flag stays
`false` until Step 8, and no step exceeds ~1 week of work. Mirror this
checklist in the Teku tracking issue (TBD).

- [x] **Step 1 — SSZ schemas.** Add `PartialDataColumnSidecar`,
      `PartialDataColumnPartsMetadata`, `PartialDataColumnHeader` (Fulu) to
      `ethereum/spec`. Wire schemas into `SchemaDefinitionsFulu`. No callers
      yet. Tests: SSZ round-trip + JSON serializer parity (unit). **Doc
      delta**: pin the consensus-spec PR commit in §2.
      *(commit: 7bd0c41e3d)*

- [x] **Step 2 — Verification helpers.** Add
      `verifyPartialDataColumnHeaderInclusionProof` and
      `verifyPartialDataColumnSidecarKzgProofs` to `MiscHelpersFulu` (and
      stubs in `MiscHelpersGloas` that throw). Tests: golden-vector tests
      against the consensus-spec test vectors when available; until then,
      construct synthetic inputs from a real `DataColumnSidecar`.
      *(commit: b9d0f55a86)*

- [ ] **Step 3 — `PartialDataColumnSidecarUtil` hierarchy.** Following the
      pattern in CLAUDE.md ("Fork-Aware Development Patterns"), create the
      abstract base, `PartialDataColumnSidecarUtilFulu`, and a stub
      `PartialDataColumnSidecarUtilGloas` (deferred). Wire into `SpecLogic`
      and add convenience method to `Spec.java`. Tests: abstract test base,
      `Fulu` impl test.

- [ ] **Step 4 — Validators.** Implement
      `PartialDataColumnHeaderGossipValidator` and
      `PartialDataColumnSidecarGossipValidator` in
      `ethereum/statetransition`. No gossip wiring yet. Tests: full
      REJECT/IGNORE matrix from §8.1 / §8.2 against `AbstractValidatorTest`.

- [ ] **Step 5 — Caches and peer-state record.** Implement
      `PartialDataColumnHeaderCache`,
      `PartialDataColumnLocalCellStore`, and the
      `PartialDataColumnPeerState` record in `networking/eth2`. Tests: LRU
      eviction, TTL behaviour, idempotent `merge` with disjoint /
      overlapping bitsets.

- [ ] **Step 6 — `PartialDataColumnSidecarHandler` (inbound only).**
      Implement `PartialMessagesHandler.onIncomingRpc` end-to-end (decode →
      validate → merge → feedback → schedule reactive publish). Stub
      `onEmitGossip` (logs only). No `Gossip.publishPartial` wiring yet —
      use a fake collaborator. Tests: 4-combo of (header? × cells?), all
      validation outcomes, reassembly trigger fires exactly once.

- [ ] **Step 7 — `PartialDataColumnSidecarPublisher`.** Implement reactive
      and metadata-only publishes (publish callsites 1 + 2 from §7.1) on top
      of `Gossip.publishPartial`. Wire `onEmitGossip` properly. Tests: in
      `networking/eth2`, with a real jvm-libp2p `Gossip` on a 2-node
      simulator.

- [ ] **Step 8 — Subscription wiring + master CLI flag.** Extend
      `DataColumnSidecarSubnetSubscriptions.subscribe(subnetId)` to pass the
      partial-message subscribe options when the master flag is on; wire
      `LibP2PGossipNetworkBuilder` to install our handler on the
      `GossipRouterBuilder`. Add the `--Xpartial-data-column-sidecar-gossip-enabled`
      flag and plumb it through `BeaconChainConfiguration` /
      `Eth2P2PNetworkBuilder`. Tests: integration test boots two Teku nodes
      with the flag on, exchanges cells, both reconstruct the full sidecar.

- [ ] **Step 9 — Reassembly + non-partial forwarding.** On reconstruction,
      deliver to `DataColumnSidecarManager` and re-`gossip(...)` over
      standard gossip. Tests: 3-node acceptance test (one full-only peer,
      two partial peers); the full-only peer must receive the full sidecar
      exactly once.

- [ ] **Step 10 — Proposer eager-push flag.** Implement publish callsite 4
      (§7.1, §9.2) with `--Xpartial-data-column-eager-push-when-proposing`.
      Tests: acceptance test asserts that on block publish, mesh peers
      receive `PartialMessagesExtension` with private-blob cells before any
      full-sidecar arrives.

- [ ] **Step 11 — Scoring rate-limit + final polish.** Implement the FMD
      rate-limit on the `USEFUL` feedback path; tune defaults; add metrics
      (per-topic counters: novel cells, dup cells, invalid cells,
      reconstructions). Tests: rate-limit unit tests; metrics smoke test.

- [ ] **Step 12 — Interop.** Spin up a Teku node alongside
      `prysm/partial-cells-current` on a devnet; run for ≥24h. Move the
      flag from `--X` to non-experimental once we have a clean run.

---

## 15. Decision log and open questions

Append entries here when design choices change. Keep most-recent on top.

### 2026-04-28 — Initial design

- Group ID: `0x00 ‖ blockRoot` (per consensus-spec PR #4558). Reject other
  version bytes.
- `PeerState` shape pinned to `(lastSeenMetadata, cellsSentToPeer,
  cellsRequestedByPeer, lastFirstDeliveryRewardTimeMillis)`. The
  `lastFirstDeliveryRewardTimeMillis` field exists to satisfy the spec FMD
  rate-limit; it goes here rather than in a sibling map because the
  jvm-libp2p `PublishAction.nextPeerState` mechanism gives us atomic updates
  for free.
- Header is cached **per-block-root globally**, not per-group. Spec
  explicitly says a header validated on any subnet is reusable on every
  subnet for that block.
- Reassembled full sidecars ship via standard `Gossip.gossip(...)`. We do not
  add a separate reassembly path. Standard gossip's TTL seen-cache prevents
  loop-back duplication.
- MVP forks: Fulu only end-to-end. Gloas variant of `PartialDataColumnHeader`
  is sketched in the SpecLogic hierarchy but tested only at the unit level.

### Open questions to resolve during implementation

- **FMD rate-limit default.** Spec says SHOULD rate-limit; doesn't specify
  the rate. Prysm value (if any) goes here once observed. Provisional
  default 250 ms.
- **Local cell store bound.** Provisional `MIN_EPOCHS_FOR_DATA_COLUMN_SIDECARS_REQUESTS
  * SLOTS_PER_EPOCH` blockRoots; revisit after measuring memory under load.
- **Header cache bound.** Same provisional bound; could be larger because a
  validated header is small (~600 bytes incl. inclusion proof).
- **Where in the `BeaconChainConfiguration` chain does the master flag
  live?** Likely `P2PConfig`; finalise during Step 8.
- **Whether to also include `PartialDataColumnHeader` validation against the
  raw block, when we already have the block locally.** Spec doesn't require
  it but it's a free win — defer to Step 4 implementation.
- **Eager-push of cells on republish back to non-partial peers**: do we want
  to prefer `Gossip.gossip(...)` or build a parallel partial path for
  partial-supporting downstream peers? Current plan = standard gossip; revise
  if measurements show meaningful redundant traffic.
- **Behaviour when a Gloas-side peer sends us a Gloas-format
  `PartialDataColumnHeader` while we are running with Fulu Spec only.**
  Reject with `INVALID`? Ignore? Resolve before Step 6 lands.
- **Lighthouse PR #8314 cross-check.** Skipped for MVP; revisit post-Step 8
  if our group-ID/metadata semantics drift from Lighthouse's.

---

## 16. References

### Spec

- [ethereum/consensus-specs PR #4558 — Add Cell Dissemination via Partial Message Specification](https://github.com/ethereum/consensus-specs/pull/4558)
- [libp2p/specs — Gossipsub Partial Messages spec (PR #685)](https://github.com/libp2p/specs/pull/685)
- [libp2p/specs — partial-messages.md @ 6b6203ee](https://github.com/libp2p/specs/blob/6b6203ee16ef2e01e6b86fc8f6c3fae0d1c6490e/pubsub/gossipsub/partial-messages.md)
- [ethresearch — Gossipsub's Partial Messages Extension and Cell-Level Dissemination](https://ethresear.ch/t/gossipsubs-partial-messages-extension-and-cell-level-dissemination/23017)

### Implementations

- [jvm-libp2p — `feature/partial-messages-support` branch](https://github.com/libp2p/jvm-libp2p/tree/feature/partial-messages-support)
- [jvm-libp2p — `docs/partial-messages.md`](https://github.com/libp2p/jvm-libp2p/blob/develop/docs/partial-messages.md)
- [OffchainLabs/prysm — `prysm/partial-cells-current` branch](https://github.com/OffchainLabs/prysm/tree/prysm/partial-cells-current)
  - [`beacon-chain/p2p/partialdatacolumnbroadcaster/`](https://github.com/OffchainLabs/prysm/tree/prysm/partial-cells-current/beacon-chain/p2p/partialdatacolumnbroadcaster)
  - [`consensus-types/blocks/partialdatacolumn.go`](https://github.com/OffchainLabs/prysm/blob/prysm/partial-cells-current/consensus-types/blocks/partialdatacolumn.go)
  - [`proto/prysm/v1alpha1/partial_data_columns.proto`](https://github.com/OffchainLabs/prysm/blob/prysm/partial-cells-current/proto/prysm/v1alpha1/partial_data_columns.proto)
- [sigp/lighthouse — PR #8314](https://github.com/sigp/lighthouse/pull/8314)

### Teku

- `ethereum/spec/.../datastructures/blobs/DataColumnSidecar.java`
- `ethereum/spec/.../logic/versions/fulu/helpers/MiscHelpersFulu.java`
- `ethereum/spec/.../logic/versions/fulu/util/DataColumnSidecarUtilFulu.java`
- `ethereum/statetransition/.../validation/DataColumnSidecarGossipValidator.java`
- `ethereum/statetransition/.../datacolumns/DataColumnSidecarManager.java`
- `networking/eth2/.../gossip/DataColumnSidecarGossipManager.java`
- `networking/eth2/.../gossip/subnets/DataColumnSidecarSubnetSubscriptions.java`
- `networking/p2p/.../libp2p/gossip/LibP2PGossipNetwork.java`
- `networking/p2p/.../libp2p/gossip/LibP2PGossipNetworkBuilder.java`
