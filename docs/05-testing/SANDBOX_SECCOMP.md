# S5 E5.4 — Syscall policy sample (seccomp)

The Studio SDK subprocess runs with the operator's UID. By default it
can issue any syscall the UID can — including `execve` (fork arbitrary
binaries), `mount` (if privileged enough), `ptrace` (read other
processes' memory), and `socket` (open any network family).

A minimal hardening pass restricts the subprocess to the small set of
syscalls the SDK actually needs:

- `read`, `write`, `open`, `close`, `stat`, `fstat`, `lstat`
- `mmap`, `mprotect`, `munmap`, `brk`
- `exit`, `exit_group`
- `rt_sigaction`, `rt_sigprocmask`, `rt_sigreturn` (signal handling)
- `clock_gettime`, `gettimeofday`
- `pipe`, `pipe2`, `dup`, `dup2`, `dup3`
- `clone` (with restrictions), `fork`, `vfork`
- `sched_yield`, `nanosleep`
- `getpid`, `gettid`, `getuid`, `getgid`, `geteuid`, `getegid`
- `set_tid_address`, `set_robust_list`
- `futex`
- `ioctl` (limited, see below)
- `socket` (limited to AF_UNIX only)
- `connect` (limited to AF_UNIX only)

The Studio does not install seccomp on the operator's behalf (D-005:
no silent kernel policy changes). The samples below are for the
operator to apply BEFORE launching `studio-app`.

## `seccomp` CLI (the portable, hand-readable form)

The recommended approach is to generate a `seccomp` profile JSON and
load it via `prctl(PR_SET_NO_NEW_PRIVS)` + `seccomp(SECCOMP_SET_MODE_FILTER)`.
Two paths:

1. **Profile JSON + Chromium's `seccomp` rule syntax** (the most
   portable): ship a JSON file, use a small loader (e.g. `seccomp-tools`
   or a 200-line Go program) to translate + apply.
2. **libseccomp + `seccomp_init`/`seccomp_rule_add`** in a small C/Go
   program: more flexible, less portable.

The Studio doesn't take a side; both are valid. The sample profile
below is the JSON form.

## Sample profile (deny-by-default)

```json
{
  "defaultAction": "SCMP_ACT_ERRNO",
  "syscalls": [
    { "names": ["read", "write", "open", "openat", "close",
                "stat", "fstat", "lstat", "fstatat",
                "mmap", "mprotect", "munmap", "brk", "madvise",
                "exit", "exit_group",
                "rt_sigaction", "rt_sigprocmask", "rt_sigreturn", "rt_sigtimedwait",
                "clock_gettime", "gettimeofday",
                "pipe", "pipe2", "dup", "dup2", "dup3",
                "clone", "fork", "vfork",
                "sched_yield", "nanosleep",
                "getpid", "gettid", "getuid", "getgid", "geteuid", "getegid",
                "set_tid_address", "set_robust_list", "arch_prctl",
                "futex", "futex_waitv",
                "ioctl",
                "close_range", "mremap"],
      "action": "SCMP_ACT_ALLOW" },
    { "names": ["socket", "connect"],
      "action": "SCMP_ACT_ALLOW",
      "args": [
        { "index": 0, "op": "SCMP_CMP_EQ", "value": 1, "valueTwo": 0 },
        { "index": 1, "op": "SCMP_CMP_EQ", "value": 1 }
      ] }
  ]
}
```

What this allows / denies:

- Everything NOT in the allow-list returns ENOSYS (the SDK subprocess
  fails fast with a clear error if it tries something unexpected).
- `socket(AF_UNIX, ...)` + `connect(AF_UNIX, ...)` are allowed so the
  subprocess can talk to the local SQLite journal via Unix sockets
  (when sqlite-jdbc opens the journal with `unix-dot-relative` paths).
  **TCP / UDP sockets are NOT allowed** — the subprocess cannot
  reach the network even with the net policy doc in place.
- `ioctl` is allowed blanket; tighten with `arg` filters per device
  if the operator knows the SDK's expected `ioctl` calls (out of scope
  for this sample).

## How to apply

```sh
# 1. Disable new privileges (required by seccomp filters).
prctl -s PR_SET_NO_NEW_PRIVS=1 $$
# 2. Load the profile. The seccomp-loader binary is operator-supplied.
seccomp-loader /etc/pipelinek-studio/seccomp.profile.json -- $$
# 3. Spawn studio-app.
exec studio-app
```

Or wrap studio-app in a thin shell script that applies the profile
before `exec`.

## How to verify

After applying the filter, attempt a forbidden syscall from inside
the subprocess context (e.g. a test scenario that tries to load a
shared library from a non-standard path, or one that calls
`System.load` on a missing file). The subprocess should:

- exit with `ENOSYS` (errno 38 on Linux) for missing syscalls
- log a clear error if the loader is set up to do so
- not produce a partial result

If the subprocess can still `execve("uname")` or `socket(AF_INET, ...)`,
the policy is misconfigured and the operator should re-check the
allow-list.

## Caveats

- `clone` is in the allow-list with no argument filters. A
  determined attacker can use it to fork+exec a binary the parent
  already mapped. Pair this policy with the net policy doc
  (`SANDBOX_NETPOLICY.md`) and a read-only filesystem namespace
  (`unshare --mount --read-only`).
- Java's JVM is syscall-hungry. Some operations (e.g. `fork`-based
  JIT) might fail with the policy above. The operator should
  smoke-test a known-good scenario (e.g. `studio create-experiment
  + studio run`) under the policy before relying on it.
- macOS doesn't support seccomp. The macOS equivalent is sandbox(7)
  with a custom `*.sb` profile, out of scope here.
- This sample is a starting point, not a complete sandbox. Pair it
  with: read-only filesystem namespace, no-new-privileges, dedicated
  UID/GID, and outbound net policy.
