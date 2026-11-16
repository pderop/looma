# Benchmarks, pr227

```
host         gabuzo-amd, Linux 7.0.0-43-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
itmt on      no (amd_pstate prefcore: disabled)
java         OpenJDK Runtime Environment (build 28-internal-pr227-5766f81)
carriers     CPUs 2-7,10-13
clients      CPUs 0,1,16,17
backend      CPUs 8,9,14,15,24,25,30,31, 8 reactors, 8 connections per pool
```

## At a glance

| scheduler | user small, cpu µs/req | user small, max req/s | user small, max req/s with p99 < 1 ms | user big, cpu µs/req | user big, max req/s | user big, max req/s with p99 < 1 ms | servlet, cpu µs/req | servlet, p99 µs | servlet, max req/s | servlet, max req/s with p99 < 1 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| mpsc | 27.92 | 610k/570k | 530k/550k | 65.06 | 170k/170k | none/none | 41.11 | 96–122 | 290k/310k | 290k/290k |
| mpsc-affine | 15.09 | 570k/850k | 570k/850k | 53.56 | 190k/190k | none/170k | 33.03 | 79–85 | 390k/390k | 350k/350k |
| mpsc-pinned | 27.23 | 610k/590k | 550k/550k | 65.23 | 170k/170k | none/none | 40.34 | 78–79 | 310k/310k | 310k/310k |
| mpsc-affine-pinned | 15.16 | 1,050k/1,070k | 1,030k/1,050k | 54.36 | 190k/190k | 170k/170k | 32.19 | 77–78 | 430k/430k | 410k/430k |
| jdk | 40.45 | 650k/670k | 470k/450k | 64.14 | 150k/170k | none/none | 55.20 | 1,997–2,148 | 350k/350k | none/none |
| jdk-pinned | 40.28 | 630k/650k | 530k/530k | 65.35 | 190k/190k | none/none | 56.07 | 115–117 | 350k/350k | 290k/290k |

Each column is copied from the At a glance of [user/summary.md](user/summary.md) and [servlet/summary.md](servlet/summary.md), whose notes say how it is
computed. The date, the heap and the command of each benchmark are in the header of its summary, the rate in its section The runs.
servlet, p99 µs: the column p99 µs of the In short of servlet/summary.md.

### The schedulers

- mpsc: the JDK's MPSC scheduler, **no carrier pinned** and **no affinity**: an actor can run
  on any carrier (`Carriers.mpsc(n)`).
- mpsc-affine: mpsc where **each actor stays on its carrier**, no carrier pinned (`Carriers.mpsc(n).affine(true)`).
- mpsc-pinned: mpsc where **each carrier is pinned to one CPU**, no affinity (`Carriers.mpsc(n).pinned(true)`).
- mpsc-affine-pinned: both, **each actor stays on its carrier** and **each carrier is pinned to one CPU** (`Carriers.mpsc(n).affine(true).pinned(true)`).
- jdk: the JDK's default scheduler, **a ForkJoinPool** (`Carriers.builtin(n)`).
- jdk-pinned: jdk, and the script **pins each worker to one carrier CPU**.
