# Benchmarks, pr227

```
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
itmt on      no (amd_pstate prefcore: disabled)
carriers     CPUs 2-7,10-13
clients      CPUs 0,1,16,17
backend      CPUs 8,9,14,15,24,25,30,31, 8 reactors, 8 connections per pool
```

## The runs

| benchmark | date | java build | Linux kernel |
|---|---|---|---|
| [user](user/summary.md) | 2026-11-22 01:37:52 +0000 | 28-internal-pr227-5766f81 | 7.0.0-43-generic |
| [servlet](servlet/summary.md) | 2026-11-22 03:30:52 +0000 | 28-internal-pr227-5766f81 | 7.0.0-43-generic |
| [servlet-skewed](servlet-skewed/summary.md) | 2026-11-22 04:07:09 +0000 | 28-internal-pr227-5766f81 | 7.0.0-43-generic |

Each benchmark can be run alone: each row comes from the header of its summary, lines date, java
and host. The host: gabuzo-amd.

## At a glance

| scheduler | user small, cpu µs/req | user small, max req/s | user small, max req/s with p99 < 1 ms | user big, cpu µs/req | user big, max req/s | user big, max req/s with p99 < 1 ms | servlet, cpu µs/req | servlet, p99 µs | servlet, max req/s | servlet, max req/s with p99 < 1 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| mpsc | 27.84 | 630k/610k | 550k/550k | 64.11 | 190k/170k | none/none | 40.12 | 78–80 | 310k/310k | 290k/310k |
| mpsc-affine | 15.32 | 890k/870k | 830k/870k | 54.06 | 190k/190k | 170k/170k | 31.81 | 74 | 390k/370k | 350k/350k |
| mpsc-affine-pinned | 14.57 | 1,070k/1,090k | 1,070k/1,090k | 54.38 | 190k/190k | 170k/170k | 31.70 | 72–76 | 430k/450k | 430k/410k |
| jdk | 40.20 | 670k/670k | 530k/510k | 64.36 | 190k/150k | none/none | 54.61 | 1,252–1,338 | 350k/330k | none/150k |

Each column is copied from the At a glance of [user/summary.md](user/summary.md) and [servlet/summary.md](servlet/summary.md), whose notes say how it is
computed. The date, the heap and the command of each benchmark are in the header of its summary, the rate in its section The runs.
servlet, p99 µs: the column p99 µs of the In short of servlet/summary.md.

## At a glance, skewed load

| scheduler | servlet, cpu µs/req | servlet, p99 µs | servlet, max req/s | servlet, max req/s with p99 < 1 ms |
|---|---|---|---|---|
| mpsc | 40.64 | 84–86 | 290k/290k | 290k/290k |
| mpsc-affine-pinned | 32.11 | 241–262 | 190k/230k | 150k/210k |
| jdk | 55.79 | 2,096–2,286 | 270k/250k | none/150k |

Each column is copied from the At a glance of [servlet-skewed/summary.md](servlet-skewed/summary.md), whose notes say how it is
computed. The date, the heap and the command of each benchmark are in the header of its summary, the rate in its section The runs.
servlet, p99 µs: the column p99 µs of the In short of servlet-skewed/summary.md.

### The schedulers

- mpsc: the JDK's MPSC scheduler, **no carrier pinned** and **no affinity**: an actor can run
  on any carrier (`Carriers.mpsc(n)`).
- mpsc-affine: mpsc where **each actor stays on its carrier**, no carrier pinned (`Carriers.mpsc(n).affine(true)`).
- mpsc-affine-pinned: both, **each actor stays on its carrier** and **each carrier is pinned to one CPU** (`Carriers.mpsc(n).affine(true).pinned(true)`).
- jdk: the JDK's default scheduler, **a ForkJoinPool** (`Carriers.builtin(n)`).
