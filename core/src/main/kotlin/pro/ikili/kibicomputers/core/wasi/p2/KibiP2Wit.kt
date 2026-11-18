package pro.ikili.kibicomputers.core.wasi.p2

// `kibi:*` の p2 のホストが出すインターフェースの WIT（#685。ADR 0685）。
//
// 中身は、`core/src/main/wit/` の p2 の world（各パッケージの `imports-p2`）をすべて import する中身のないコンポーネントを作り、
// `wasm-tools component wit` で書き出したもののうち、`package kibi:` の塊だけを並べたもの（手で書き換えない）。
// WIT を直したら、`KibiP2HostTest` の「ホストのWITはsrc_main_witと同じ」が差分を示すので、書き出し直す。
// `wasi:*` の参照（`use wasi:io/poll@0.2.12.{pollable}` など）は [WasiP2Wit.BASE_VERSION] の版で書き、`WasiP2Host` が読んだ型に解決する。

/** ホストの `kibi:*` の p2 の WIT */
internal object KibiP2Wit {
    /** `package kibi:<pkg>@0.1.0 { ... }` を、依存の順に並べたもの */
    val TEXT: String = """
package kibi:kernel@0.1.0 {
  interface types {
    enum error-code {
      too-big,
      access,
      addr-in-use,
      addr-not-avail,
      af-not-supported,
      again,
      already,
      bad-descriptor,
      bad-message,
      busy,
      canceled,
      child,
      conn-aborted,
      conn-refused,
      conn-reset,
      deadlock,
      dest-addr-required,
      domain,
      quota,
      exist,
      fault,
      file-too-large,
      host-unreachable,
      identifier-removed,
      illegal-byte-sequence,
      in-progress,
      interrupted,
      invalid,
      io,
      is-connected,
      is-directory,
      loop,
      mfile,
      too-many-links,
      message-size,
      multihop,
      name-too-long,
      net-down,
      net-reset,
      net-unreachable,
      nfile,
      no-buffers,
      no-device,
      no-entry,
      no-exec,
      no-lock,
      no-link,
      no-memory,
      no-message,
      no-protocol-option,
      no-space,
      no-sys,
      not-connected,
      not-directory,
      not-empty,
      not-recoverable,
      not-socket,
      not-supported,
      no-tty,
      no-such-device,
      overflow,
      owner-dead,
      not-permitted,
      pipe,
      protocol,
      protocol-not-supported,
      protocol-type,
      range,
      read-only,
      invalid-seek,
      no-such-process,
      stale,
      timed-out,
      text-file-busy,
      cross-device,
      not-capable,
    }
  }
  interface events {
    use types.{error-code};

    resource event-queue {
      open: static func(kinds: list<string>, capacity: option<u32>) -> result<event-queue, error-code>;
      take: func(max: u32) -> result<event-batch, error-code>;
    }

    variant event-value {
      integer(s64),
      float(f64),
      boolean(bool),
      text(string),
      bytes(list<u8>),
    }

    record event {
      kind: string,
      source: string,
      values: list<event-value>,
    }

    record event-batch {
      events: list<event>,
      dropped: u64,
    }
  }
  interface timers {
    use events.{event-queue};
    use types.{error-code};

    resource timer {
      create: static func(events: option<tuple<borrow<event-queue>, s64>>) -> result<timer, error-code>;
      set-after: func(duration: u64, interval: u64) -> result<_, error-code>;
      set-at: func(instant: u64, interval: u64) -> result<_, error-code>;
      cancel: func() -> result<_, error-code>;
      read: func() -> result<u64, error-code>;
    }
  }
  interface timers-poll {
    use timers.{timer};
    use wasi:io/poll@0.2.12.{pollable};

    subscribe: func(t: borrow<timer>) -> pollable;
  }
  interface events-poll {
    use events.{event-queue};
    use wasi:io/poll@0.2.12.{pollable};

    subscribe: func(q: borrow<event-queue>) -> pollable;
  }
}

package kibi:io@0.1.0 {
  interface pipe-streams {
    use wasi:io/streams@0.2.12.{input-stream, output-stream};
    use kibi:kernel/types@0.1.0.{error-code};

    create: func() -> result<tuple<input-stream, output-stream>, error-code>;
  }
  interface tty {
    use kibi:kernel/types@0.1.0.{error-code};

    flags mode {
      canonical,
      echo,
      signals,
      cr-to-nl,
      nl-to-crnl,
    }

    record size {
      columns: u32,
      rows: u32,
      generation: u64,
    }

    is-tty: func(fd: u32) -> bool;

    get-mode: func(fd: u32) -> result<mode, error-code>;

    set-mode: func(fd: u32, mode: mode) -> result<_, error-code>;

    get-size: func(fd: u32) -> result<size, error-code>;

    get-foreground: func(fd: u32) -> result<u32, error-code>;

    set-foreground: func(fd: u32, pid: u32) -> result<_, error-code>;
  }
}

package kibi:process@0.1.0 {
  interface process {
    use kibi:kernel/types@0.1.0.{error-code};

    resource process {
      spawn: static func(path: string, options: spawn-options) -> result<process, error-code>;
      pid: func() -> u32;
      status: func() -> result<option<exit-status>, error-code>;
      kill: func(sig: signal) -> result<_, error-code>;
    }

    variant fd-source {
      parent(u32),
      closed,
    }

    record fd-assign {
      fd: u32,
      source: fd-source,
    }

    record spawn-options {
      args: list<string>,
      env: option<list<tuple<string, string>>>,
      cwd: option<string>,
      fds: list<fd-assign>,
    }

    enum signal {
      interrupt,
      quit,
      terminate,
      kill,
    }

    variant exit-status {
      exited(u32),
      killed(option<signal>),
      crashed,
    }

    variant process-state {
      starting,
      running,
      waiting,
      finished(exit-status),
    }

    record process-info {
      pid: u32,
      parent-pid: u32,
      state: process-state,
      args: list<string>,
    }

    kill-pid: func(pid: u32, sig: signal) -> result<_, error-code>;

    list-processes: func() -> list<process-info>;

    self-pid: func() -> u32;

    parent-pid: func() -> u32;

    set-signal-ignored: func(sig: signal, ignored: bool) -> result<_, error-code>;

    get-cwd: func() -> string;

    set-cwd: func(path: string) -> result<_, error-code>;
  }
  interface process-poll {
    use process.{process};
    use wasi:io/poll@0.2.12.{pollable};
    use process.{spawn-options};
    use wasi:io/streams@0.2.12.{input-stream, output-stream};
    use wasi:filesystem/types@0.2.12.{descriptor};
    use kibi:kernel/types@0.1.0.{error-code};

    variant handle-source {
      input(borrow<input-stream>),
      output(borrow<output-stream>),
      directory(borrow<descriptor>),
      process(borrow<process>),
    }

    record handle-assign {
      fd: u32,
      source: handle-source,
    }

    subscribe: func(p: borrow<process>) -> pollable;

    spawn-with-handles: func(path: string, options: spawn-options, handles: list<handle-assign>) -> result<process, error-code>;
  }
}

package kibi:fs@0.1.0 {
  interface mounts {
    use kibi:kernel/types@0.1.0.{error-code};

    enum fs-kind {
      disk,
      image,
      tmp,
      dev,
    }

    record fs-usage {
      total-bytes: u64,
      used-bytes: u64,
      available-bytes: u64,
      total-entries: option<u32>,
      used-entries: u32,
    }

    record mount-info {
      point: string,
      source: string,
      kind: fs-kind,
      read-only: bool,
      id: u32,
      usage: option<fs-usage>,
    }

    record disk-info {
      source: string,
      capacity-bytes: u64,
      max-entries: u32,
      mount-point: option<string>,
    }

    mount: func(source: string, point: string, read-only: bool) -> result<_, error-code>;

    unmount: func(point: string) -> result<_, error-code>;

    list-mounts: func() -> list<mount-info>;

    list-disks: func() -> list<disk-info>;
  }
}

package kibi:device@0.1.0 {
  interface types {
    use kibi:kernel/types@0.1.0.{error-code};

    record device-info {
      address: string,
      kind: string,
    }

    variant call-error {
      kernel(error-code),
      detached,
      no-such-method,
      bad-arguments(string),
      failed(string),
    }

    record method-info {
      name: string,
      main-thread: bool,
    }

    variant value {
      none,
      integer(s64),
      float(f64),
      boolean(bool),
      text(string),
      bytes(list<u8>),
    }
  }
  interface bus {
    use types.{device-info, call-error, method-info, value};

    resource device {
      open: static func(address: string) -> result<device, call-error>;
      info: func() -> device-info;
      methods: func() -> list<method-info>;
      invoke: func(method: string, args: list<value>) -> result<list<value>, call-error>;
      start: func(method: string, args: list<value>) -> result<pending-call, call-error>;
    }

    resource pending-call {
      take: func() -> option<result<list<value>, call-error>>;
    }

    list-devices: func() -> list<device-info>;
  }
  interface bus-poll {
    use bus.{pending-call};
    use wasi:io/poll@0.2.12.{pollable};

    subscribe: func(call: borrow<pending-call>) -> pollable;
  }
}

package kibi:computer@0.1.0 {
  interface memory {
    remaining: func() -> u64;

    commit: func(ptr: u32, len: u32, keep: u64) -> bool;
  }
  interface clock {
    uptime-ticks: func() -> u64;

    world-ticks: func() -> u64;
  }
  interface power {
    use kibi:kernel/types@0.1.0.{error-code};

    shutdown: func();

    reboot: func() -> result<_, error-code>;
  }
  interface access {
    use kibi:kernel/types@0.1.0.{error-code};

    enum access-state {
      unowned,
      owned,
      locked,
    }

    record player {
      name: string,
      id: string,
    }

    record access-info {
      state: access-state,
      owner: option<player>,
      members: list<player>,
      invites: list<string>,
    }

    get-access: func() -> result<access-info, error-code>;

    request-add: func(name: string) -> result<_, error-code>;

    request-remove: func(name-or-id: string) -> result<_, error-code>;

    request-transfer: func(name: string) -> result<_, error-code>;
  }
}
""".trimStart()
}
