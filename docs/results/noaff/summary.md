# Benchmarks, noaff

```
host         gabuzo-amd, Linux 7.0.0-43-generic
cpu model    AMD Ryzen 9 9950X 16-Core Processor
turbo        off
itmt on      no (amd_pstate prefcore: disabled)
java         OpenJDK Runtime Environment (build 28-internal-noaff-33d7cd3)
carriers     CPUs 2-7,10-13
clients      CPUs 0,1,16,17
backend      CPUs 8,9,14,15,24,25,30,31, 8 reactors, 8 connections per pool
```

## At a glance

| scheduler | user small, cpu µs/req | user small, max req/s | user small, max req/s with p99 < 1 ms | user big, cpu µs/req | user big, max req/s | user big, max req/s with p99 < 1 ms | servlet, cpu µs/req | servlet, p99 µs | servlet, max req/s | servlet, max req/s with p99 < 1 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| mpsc | 29.86 | 830k/850k | 690k/690k | 64.97 | 150k/190k | none/none | 45.40 (r2) | 85 (r2) | 370k/370k | 310k/330k |
| jdk | 40.58 | 650k/690k | 510k/510k | 64.41 | 150k/150k | none/none | 55.02 | 1,736–2,181 | 330k/330k | none/none |

Each column is copied from the At a glance of [user/summary.md](user/summary.md) and [servlet/summary.md](servlet/summary.md), whose notes say how it is
computed. The date, the heap and the command of each benchmark are in the header of its summary, the rate in its section The runs.
servlet, p99 µs: the column p99 µs of the In short of servlet/summary.md.

### The schedulers

- mpsc: the JDK's MPSC scheduler, **no carrier pinned** and **no affinity**: an actor can run
  on any carrier (`Carriers.mpsc(n)`).
- jdk: the JDK's default scheduler, **a ForkJoinPool** (`Carriers.builtin(n)`).
