# Benchmarks

```
date         2026-11-11 23:43:08 +0000
host         gabuzo-amd, Linux 7.0.0-42-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
itmt on      no (amd_pstate prefcore: disabled)
java         OpenJDK Runtime Environment (build 28-internal-pr227-5766f81)
carriers     CPUs 2-7,10-13
clients      CPUs 0,1,16,17
backend      CPUs 8,9,14,15,24,25,30,31, 8 reactors, 8 connections per pool
```

## At a glance

| scheduler | user default, cpu µs/req | user big, cpu µs/req | user default, max req/s | user big, max req/s | user default, max req/s with p99 < 1 ms | user big, max req/s with p99 < 1 ms | servlet, cpu µs/req | servlet, p99 µs | servlet, max req/s | servlet, max req/s with p99 < 1 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| mpsc | 14.60 | 53.85 | 1,070,000 / 1,090,000 | 190,000 / 190,000 | 1,070,000 / 1,070,000 | 170,000 / 190,000 | 31.88 | 75–79 | 430,000 / 430,000 | 430,000 / 410,000 |
| mpsc-unpinned | 15.37 | 53.54 | 890,000 / 930,000 | 190,000 / 190,000 | 830,000 / 850,000 | 170,000 / 170,000 | 31.91 | 74 | 430,000 / 390,000 | 390,000 / 350,000 |
| mpsc-no-affinity | 26.76 | 65.19 | 630,000 / 630,000 | 190,000 / 170,000 | 570,000 / 570,000 | none / none | 39.58 | 74–77 | 310,000 / 310,000 | 310,000 / 310,000 |
| mpsc-unpinned-no-affinity | 27.57 | 64.77 | 610,000 / 630,000 | 170,000 / 170,000 | 550,000 / 550,000 | none / none | 40.23 | 78–84 | 310,000 / 310,000 | 310,000 / 290,000 |
| jdk | 40.34 | 64.54 | 670,000 / 690,000 | 170,000 / 170,000 | 530,000 / 510,000 | none / none | 54.48 | 1,218–1,701 | 350,000 / 330,000 | none / none |
| jdk-pinned | 39.81 | 65.03 | 670,000 / 690,000 | 190,000 / 190,000 | 530,000 / 530,000 | none / none | 54.15 | 104–107 | 370,000 / 330,000 | 290,000 / 290,000 |

Each column is copied from the At a glance of [user/summary.md](user/summary.md) and [servlet/summary.md](servlet/summary.md), whose notes say how it is
computed. The rate, the heap and the command of each benchmark are in the header of its summary.
servlet, p99 µs: the column p99 µs of the In short of servlet/summary.md.

#### The schedulers

- mpsc: the MPSC scheduler of openjdk/loom pull request 227, with
  `-Djdk.virtualThreadScheduler.useMpsc=true -Djdk.virtualThreadScheduler.mpscSteal=true`.
  Looma **pins each carrier to one CPU**. An actor's threads have the `Thread.Affinity` of its
  carrier: **never stolen**. The threads an actor starts have none: **an idle carrier can steal them**.
- mpsc-unpinned: mpsc, **no carrier pinned**.
- mpsc-no-affinity: mpsc, **carriers pinned**, but an actor's threads have no affinity: **they start on the carrier of the sender, and an idle carrier can steal them**.
- mpsc-unpinned-no-affinity: both, **no carrier pinned** and **no affinity**.
- jdk: the JDK's default scheduler, **a ForkJoinPool**.
- jdk-pinned: jdk, and the script **pins each worker to one carrier CPU**.
