# Waggle prototype (EIP-8334 attestation propagation)

This branch is a prototype. It is not meant to be merged as is.

Behind the hidden flag `--Xp2p-waggle-attestations-enabled`, subnet attestations also propagate
over the Waggle pubsub protocol (libp2p/specs#732, draft) next to gossipsub, following the
EIP-8334 draft (ethereum/EIPs#11905 at f7f0cfb). Without the flag nothing changes.

## What is here

- `networking/p2p`: `WaggleNetwork`, a frame-level API over Waggle, and its jvm-libp2p
  implementation `LibP2PWaggleNetwork`. Registered on the libp2p host and announced in identify
  when the flag is set.
- `networking/eth2`, package `gossip.waggle`: the EIP-8334 containers and
  `WaggleAttestationPropagator`, which pushes, advertises, requests and serves attestations per
  subnet. `AttestationSubnetSubscriptions` feeds it every attestation that passes gossip
  validation and every attestation we publish, and makes it follow the subnet subscriptions.
  Attestations received over Waggle go through the regular gossip validation and, when valid, are
  published on gossipsub.

## Wire format

The format follows Prysm (sukunrt/prysm, branch `eip-8334-waggle` at 3a20cd9), the only other
implementation:

- Topic: the gossipsub topic string, `/eth2/{fork_digest}/beacon_attestation_{subnet}/ssz_snappy`.
- Object id: the slot, 8 bytes little endian.
- Pieces: SSZ `AttestationBundle`, not compressed.
- Metadata: SSZ `CommitteeAttestationPartsMetadata` with a `data_root` field that the EIP text
  does not have. Prysm keys a vote by (data root, validator); so does this.

## Building

jvm-libp2p has no Waggle release, so this branch depends on `io.libp2p:jvm-libp2p:waggle-prototype`
from the local Maven repository:

```bash
git clone -b waggle-prototype https://github.com/lucassaldanha/jvm-libp2p
(cd jvm-libp2p && ./gradlew :libp2p:publishToMavenLocal)
./gradlew installDist
```

## Running against Prysm with Kurtosis

`kurtosis-teku-prysm.yaml` is an ethereum-package args file for a two-node devnet. It expects
three local images:

- `teku-waggle:local`: `build/install/teku` copied to `/opt/teku` on a Java 25 JRE image.
- `prysm-waggle-bn:local` and `prysm-waggle-vc:local`: `go build ./cmd/beacon-chain` and
  `./cmd/validator` from the Prysm branch, at `/beacon-chain` and `/app/cmd/validator/validator`.

```bash
kurtosis run --enclave waggle github.com/ethpandaops/ethereum-package --args-file docs/waggle/kurtosis-teku-prysm.yaml
```

It needs Kurtosis 1.23.0 or newer. Teku exposes
`network_waggle_attestation_events_total{event=...}` on its metrics port; Prysm has no Waggle
metrics, so run it with `cl_log_level: debug` and look for `Rejected attestation from waggle`.

## Known gaps

- Peers that send invalid attestations or malformed frames are counted and logged, not scored.
- No Waggle integration or acceptance test in this repository; only unit tests of the propagator.
- The propagator's parameters are fixed at the EIP's recommended values.
- An attestation that arrives over both paths is validated once and ignored once; the second
  arrival is not deduplicated before validation.
- Only forks whose subnet attestations are `SingleAttestation` (Electra onwards) are covered.
- jvm-libp2p's Yamux and Mplex streams do not report write progress, so Waggle's per-connection
  queue bound is not effective; the muxer's buffer limit is.
