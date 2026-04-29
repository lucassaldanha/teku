# Partial Messages Review

Reviewed diff: `7bd0c41e3d..a01241f5c4`

This review covers the partial data column sidecar gossip work added across `ethereum/spec`,
`ethereum/statetransition`, `networking/eth2`, `networking/p2p`, `teku`, and
`docs/partial-messages.md`.

## Actionable Findings

### 1. Partial-messages handlers are not wired into the live network path

Severity: High

`Eth2P2PNetworkBuilder` only forwards `config.isPartialDataColumnGossipEnabled()` into the libp2p
builder (`networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/Eth2P2PNetworkBuilder.java:545`),
and `LibP2PGossipNetworkBuilder` only enables the gossipsub extension
(`networking/p2p/src/main/java/tech/pegasys/teku/networking/p2p/libp2p/gossip/LibP2PGossipNetworkBuilder.java:159`).
The new `PartialDataColumnSidecarHandler`, `PartialDataColumnSidecarPublisher`, and
`PartialDataColumnReassembler` have no production call sites outside tests.

Impact: enabling `--Xpartial-data-column-sidecar-gossip-enabled` negotiates the extension but does
not validate inbound partial RPCs, update partial peer state, publish partial payloads, or
reconstruct full sidecars.

Action: add production wiring from data column sidecar gossip subscriptions into the partial handler,
publisher, header cache, cell store, validators, and reassembler, and add an integration test that
starts the feature through `P2PConfig`.

### 2. Heartbeat metadata publishing is still a stub

Severity: High

`PartialDataColumnSidecarHandler.onEmitGossip` logs that the publisher is not implemented and then
returns (`networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/partialmessages/PartialDataColumnSidecarHandler.java:179`).

Impact: partial-capable peers will not receive our availability metadata during heartbeat emission,
so they cannot learn which cells to request from us through the extension.

Action: inject `PartialDataColumnSidecarPublisher` into the handler and call
`publishMetadataOnly(topic, blockRoot, columnIndex)` from `onEmitGossip`, including group ID parsing
and error handling.

### 3. Incoming metadata never updates peer state

Severity: High

`processAsync` receives `peerStates` but marks it unused, then only logs received metadata
(`networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/partialmessages/PartialDataColumnSidecarHandler.java:198`
and `:326`). `PartialDataColumnSidecarPublisher` depends on `state.cellsRequestedByPeer()` to decide
what to send (`networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/partialmessages/PartialDataColumnSidecarPublisher.java:181`).

Impact: peers' `requests` bits are never persisted into `PartialDataColumnPeerState`, so reactive
publishing cannot know what a peer wants. The FMD state in `PartialDataColumnPeerState` is also never
applied before reporting `FeedbackKind.USEFUL`.

Action: return or apply a `PublishAction.nextPeerState` update after metadata validation, merge
requests via `PartialDataColumnPeerState.withUpdatedMetadata`, and enforce the FMD reward limit
before reporting useful feedback.

### 4. Reconstruction can never trigger

Severity: High

After merging novel cells, the handler checks `totalExpected > 0` before calling the reconstruction
listener (`networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/partialmessages/PartialDataColumnSidecarHandler.java:313`).
`computeTotalExpected` always returns `0`
(`networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/partialmessages/PartialDataColumnSidecarHandler.java:392`).

Impact: even if all cells arrive and validate, the full `DataColumnSidecar` is never reconstructed
or delivered to the existing sidecar pipeline.

Action: look up the validated header for `blockRoot` and return
`header.getKzgCommitments().size()`. Add a test that sends all cells for one column and verifies
`ReconstructionListener.onReconstructionComplete` is called exactly once.

### 5. Reconstructed full sidecars are not re-gossiped

Severity: Medium

`PartialDataColumnReassembler.gossipReconstructed` serializes the sidecar but only logs a TODO
instead of publishing through the existing data-column gossip manager
(`networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/partialmessages/PartialDataColumnReassembler.java:163`).

Impact: non-partial peers never receive the reconstructed full sidecar from this path, which breaks
the intended bridge between partial-capable and standard gossip peers.

Action: inject the existing `DataColumnSidecarGossipManager` or `DataColumnSidecarSubnetSubscriptions`
publish path and call it from the reassembler after successful manager ingestion.

### 6. Cell/proof storage loses blob-index ordering

Severity: High

`PartialDataColumnLocalCellStore.merge` appends new cells and proofs in arrival order while setting
their blob-index bits (`networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/partialmessages/PartialDataColumnLocalCellStore.java:117`).
Later, `PartialDataColumnSidecarPublisher` iterates the sorted `available` bitset and uses a
sequential `entryIndex` to fetch cells/proofs (`networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/partialmessages/PartialDataColumnSidecarPublisher.java:257`).
`PartialDataColumnReassembler` also assumes `entry.cells()` is already in blob-index order
(`networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/partialmessages/PartialDataColumnReassembler.java:109`).

Impact: receiving blob index 3 before blob index 1 stores `[cell3, cell1]` while `available` is
`{1,3}`. The publisher can then send `cell3` for bit 1, and the reassembler can build a column with
cells in the wrong order.

Action: store cells and proofs keyed by blob index, or insert them into lists at the position implied
by the bit index. Add tests that merge out-of-order, sparse bitmaps and then publish/reassemble.

### 7. Headerless cell messages skip cell/proof count validation

Severity: Medium

The count check `len(partial_column) == len(kzg_proofs) == popcount(cells_present_bitmap)` is only
implemented in `PartialDataColumnHeaderGossipValidator`
(`ethereum/statetransition/src/main/java/tech/pegasys/teku/statetransition/validation/PartialDataColumnHeaderGossipValidator.java:110`).
The cell validator does not repeat that check before calling KZG verification
(`ethereum/statetransition/src/main/java/tech/pegasys/teku/statetransition/validation/PartialDataColumnSidecarGossipValidator.java:100`).
`MiscHelpersFulu.verifyPartialDataColumnSidecarKzgProofs` then indexes `partialColumn` and
`kzgProofs` by each set bit (`ethereum/spec/src/main/java/tech/pegasys/teku/spec/logic/versions/fulu/helpers/MiscHelpersFulu.java:374`).

Impact: a malformed headerless cell-only partial message can throw during validation instead of
returning `REJECT`, or can include extra cells/proofs that are ignored by verification.

Action: move the count check into the cell validator, or share it between header and cell validators,
and reject malformed headerless sidecars before KZG verification.

### 8. Publisher emits short bitlists that the validator rejects

Severity: High

`PartialDataColumnSidecarGossipValidator` requires
`cells_present_bitmap.size() == header.getKzgCommitments().size()`
(`ethereum/statetransition/src/main/java/tech/pegasys/teku/statetransition/validation/PartialDataColumnSidecarGossipValidator.java:100`).
`PartialDataColumnSidecarPublisher` builds the sidecar bitmap with
`Math.max(toSend.length(), 1)`, where `BitSet.length()` is only the highest set bit plus one
(`networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/partialmessages/PartialDataColumnSidecarPublisher.java:270`).
Metadata uses the same short sizing pattern
(`networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/partialmessages/PartialDataColumnSidecarPublisher.java:289`).

Impact: if a block has 6 commitments and we send only blob index 0, the publisher encodes a
1-bit bitmap, and any conforming receiver rejects it because it expects a 6-bit bitmap.

Action: size sidecar and metadata bitlists from the validated header commitment count, not from the
highest set bit in the local `BitSet`.

### 9. Gloas partial util throws at runtime

Severity: Medium

`PartialDataColumnSidecarUtilGloas` implements both methods by throwing
`UnsupportedOperationException`
(`ethereum/spec/src/main/java/tech/pegasys/teku/spec/logic/versions/gloas/util/PartialDataColumnSidecarUtilGloas.java:30`).
The partial feature flag is network-wide, and the validators call
`spec.getPartialDataColumnSidecarUtil(slot)` based on the message slot.

Impact: if partial gossip is enabled around a Gloas slot, malformed or unexpected partial messages
can turn into unhandled async errors instead of clean `IGNORE` or `REJECT` outcomes.

Action: disable partial data column handling for unsupported milestones before validation, or make
the Gloas util return deterministic validation failures until the Gloas format is implemented.

### 10. The jvm-libp2p dependency is changed to a mutable `develop` version

Severity: Medium

`gradle/versions.gradle` changes `io.libp2p:jvm-libp2p` from `1.2.2-RELEASE` to `develop`
(`gradle/versions.gradle:40`).

Impact: builds become non-reproducible and may fail in environments that cannot resolve a
non-release or mutable version. It also makes CI behavior dependent on the current state of an
upstream development artifact.

Action: depend on a published immutable release or commit-pinned artifact that contains the partial
messages API, or keep the release dependency and isolate partial-messages integration behind an
adapter that compiles without unreleased APIs.

## Consolidated Remediation Checklist

- [ ] Wire the partial handler, publisher, header cache, cell store, validators, and reassembler into
      the production data-column gossip path.
- [ ] Add an end-to-end or integration test that enables
      `--Xpartial-data-column-sidecar-gossip-enabled` and exercises inbound partial RPC handling.
- [ ] Implement `onEmitGossip` metadata publishing through `PartialDataColumnSidecarPublisher`.
- [ ] Apply incoming metadata to `PartialDataColumnPeerState` and persist request bits for reactive
      publishing.
- [ ] Enforce first-message-delivery reward rate limiting before reporting useful peer feedback.
- [ ] Replace `computeTotalExpected == 0` with validated-header commitment count lookup.
- [ ] Publish reconstructed full sidecars through the existing data-column sidecar gossip manager.
- [ ] Store cells/proofs by blob index, and cover out-of-order sparse arrival in tests.
- [ ] Validate cell/proof/popcount equality in the cell validator for headerless messages.
- [ ] Size all sidecar and metadata bitlists from the header commitment count.
- [ ] Gate or implement Gloas partial handling so unsupported slots do not throw.
- [ ] Replace the `develop` jvm-libp2p dependency with an immutable, reproducible artifact.
