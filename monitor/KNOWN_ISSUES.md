# Monitor Known Issues

## Network metrics unreliable (net_rx_mb / net_tx_mb always 0.0)

The `/proc/net/dev` parser is likely not matching the actual network interface name on this Hetzner box (probably `eth0`, `ens3`, or similar). The interface may be getting filtered out or the line format isn't being parsed correctly. Fix this before relying on network numbers.

**Priority:** Low — RAM and disk are the metrics that matter for OBA monitoring.

## CPU % is noisy

Single point-in-time reading (two `/proc/stat` snapshots 1s apart, not averaged). Individual samples can spike high. Not a bug — just low fidelity. Consider averaging over multiple reads if smoothed CPU becomes important.

## Container cpu_pct often 0.0

The Docker stats API `?stream=false` returns `cpu_stats` and `precpu_stats` from nearly the same moment, so the delta is often zero. Only meaningful if the container was actively doing work right at sample time. A proper fix would require two sequential stats calls with a sleep between them, or switching to streaming mode.
