# Single- and multi-module benchmark results

50 screenshots in each fixture; multi-module means an app and four libraries with 10 screenshots each.
Wall-clock medians of three measured runs following an untimed round. See [methodology](README.md).

| Library | Single record | Single verify | Multi record | Multi verify |
|---|---:|---:|---:|---:|
| Medusa | 6.5 s | 12.5 s | 6.2 s | 6.9 s |
| Paparazzi | 7.6 s | 8.3 s | 10.3 s | 10.3 s |
| Roborazzi | 9.2 s | 9.5 s | 14.1 s | 14.5 s |
| Google Compose Screenshot Testing | 6.7 s | 6.6 s | 10.5 s | 10.6 s |
| Shot | 41.1 s | 41.1 s | 76.9 s | 78.1 s |
| Dropshots | 41.6 s | 40.9 s | 75.3 s | 75.4 s |

## All measured ranges

| Library | Project | Operation | Median | Min–max |
|---|---|---|---:|---:|
| Medusa | single | record | 6.50 s | 6.48–6.51 s |
| Medusa | single | verify | 12.54 s | 12.53–12.69 s |
| Medusa | multi | record | 6.17 s | 6.17–7.25 s |
| Medusa | multi | verify | 6.91 s | 6.80–14.20 s |
| Paparazzi | single | record | 7.65 s | 7.59–7.92 s |
| Paparazzi | single | verify | 8.26 s | 8.11–8.28 s |
| Paparazzi | multi | record | 10.27 s | 10.27–10.42 s |
| Paparazzi | multi | verify | 10.28 s | 9.69–10.41 s |
| Roborazzi | single | record | 9.20 s | 8.96–9.25 s |
| Roborazzi | single | verify | 9.50 s | 9.28–10.05 s |
| Roborazzi | multi | record | 14.10 s | 13.97–14.18 s |
| Roborazzi | multi | verify | 14.46 s | 14.33–14.82 s |
| Google Compose Screenshot Testing | single | record | 6.65 s | 6.63–6.74 s |
| Google Compose Screenshot Testing | single | verify | 6.61 s | 6.60–6.65 s |
| Google Compose Screenshot Testing | multi | record | 10.54 s | 10.52–10.63 s |
| Google Compose Screenshot Testing | multi | verify | 10.59 s | 10.58–10.60 s |
| Shot | single | record | 41.06 s | 40.00–42.04 s |
| Shot | single | verify | 41.15 s | 40.68–44.90 s |
| Shot | multi | record | 76.88 s | 76.13–80.02 s |
| Shot | multi | verify | 78.11 s | 77.50–82.92 s |
| Dropshots | single | record | 41.60 s | 41.17–41.85 s |
| Dropshots | single | verify | 40.95 s | 39.65–41.13 s |
| Dropshots | multi | record | 75.30 s | 74.64–77.93 s |
| Dropshots | multi | verify | 75.42 s | 72.36–75.76 s |

Every successful verification covered 50 tests with no failures, errors or skips. Every fixture passed a deliberate-mismatch control and a restored-source verification.

[Raw timings and controls](results.json).
