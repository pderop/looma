# Benchmarks, noaff

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
| [user](user/summary.md) | 2026-11-22 04:25:35 +0000 | 28-internal-noaff-33d7cd3 | 7.0.0-43-generic |
| [servlet](servlet/summary.md) | 2026-11-22 04:54:58 +0000 | 28-internal-noaff-33d7cd3 | 7.0.0-43-generic |
| [servlet-skewed](servlet-skewed/summary.md) | 2026-11-22 05:04:16 +0000 | 28-internal-noaff-33d7cd3 | 7.0.0-43-generic |

Each benchmark can be run alone: each row comes from the header of its summary, lines date, java
and host. The host: gabuzo-amd.

## At a glance

| scheduler | user small, cpu µs/req | user small, max req/s | user small, max req/s with p99 < 1 ms | user big, cpu µs/req | user big, max req/s | user big, max req/s with p99 < 1 ms | servlet, cpu µs/req | servlet, p99 µs | servlet, max req/s | servlet, max req/s with p99 < 1 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| mpsc | 29.29 | 870k/830k | 770k/750k | 64.41 | 190k/190k | none/none | 44.04 (r1) | 83 (r1) | 350k/370k | 330k/330k |

Each column is copied from the At a glance of [user/summary.md](user/summary.md) and [servlet/summary.md](servlet/summary.md), whose notes say how it is
computed. The date, the heap and the command of each benchmark are in the header of its summary, the rate in its section The runs.
servlet, p99 µs: the column p99 µs of the In short of servlet/summary.md.

## At a glance, skewed load

| scheduler | servlet, cpu µs/req | servlet, p99 µs | servlet, max req/s | servlet, max req/s with p99 < 1 ms |
|---|---|---|---|---|
| mpsc | 44.54 | 86–87 | 350k/370k | 290k/310k |

Each column is copied from the At a glance of [servlet-skewed/summary.md](servlet-skewed/summary.md), whose notes say how it is
computed. The date, the heap and the command of each benchmark are in the header of its summary, the rate in its section The runs.
servlet, p99 µs: the column p99 µs of the In short of servlet-skewed/summary.md.

### The schedulers

- mpsc: the JDK's MPSC scheduler, **no carrier pinned** and **no affinity**: an actor can run
  on any carrier (`Carriers.mpsc(n)`).
