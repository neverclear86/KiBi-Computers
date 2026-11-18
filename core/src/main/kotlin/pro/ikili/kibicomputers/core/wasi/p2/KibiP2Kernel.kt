package pro.ikili.kibicomputers.core.wasi.p2

import pro.ikili.kibicomputers.core.access.AccessRequestKind
import pro.ikili.kibicomputers.core.kernel.event.EventBatch
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.io.PipeEnd
import pro.ikili.kibicomputers.core.kernel.io.Pipes
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.DEFAULT_HANDLE_LIMIT
import pro.ikili.kibicomputers.core.kernel.process.HostResourceTable
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.HostFunctionBody
import pro.ikili.kibicomputers.core.wasi.kibi.KibiAccess
import pro.ikili.kibicomputers.core.wasi.kibi.KibiClock
import pro.ikili.kibicomputers.core.wasi.kibi.KibiEvents
import pro.ikili.kibicomputers.core.wasi.kibi.KibiFs
import pro.ikili.kibicomputers.core.wasi.kibi.KibiMemory
import pro.ikili.kibicomputers.core.wasi.kibi.KibiPower
import pro.ikili.kibicomputers.core.wasi.kibi.KibiTty
import pro.ikili.kibicomputers.core.wasi.p2.KibiP2.body

/**
 * `kibi:kernel`（events・timers・`*-poll`）、`kibi:io`（tty・pipe-streams）、`kibi:fs/mounts`、`kibi:computer`（memory・clock・power・access）の
 * p2 の実装（#685。ADR 0685）。意味は p1 と同じ層（`wasi/kibi/Kibi*`）とカーネルの口を使う。文字列の上限は p1 の lift と同じ
 * （超えたら同じ errno）。燃料は p1 と同じ（`take` はイベントの数、一覧は件数）
 */
internal object KibiP2Kernel {
    const val EVENTS: String = "kibi:kernel/events"
    const val TIMERS: String = "kibi:kernel/timers"
    const val EVENTS_POLL: String = "kibi:kernel/events-poll"
    const val TIMERS_POLL: String = "kibi:kernel/timers-poll"
    const val TTY: String = "kibi:io/tty"
    const val PIPE_STREAMS: String = "kibi:io/pipe-streams"
    const val MOUNTS: String = "kibi:fs/mounts"
    const val MEMORY: String = "kibi:computer/memory"
    const val CLOCK: String = "kibi:computer/clock"
    const val POWER: String = "kibi:computer/power"
    const val ACCESS: String = "kibi:computer/access"

    const val EVENT_QUEUE: String = "event-queue"
    const val TIMER: String = "timer"

    fun functions(t: WasiP2Types): Map<String, Map<String, HostFunctionBody>> {
        val events = mapOf(
            "[static]event-queue.open" to body { ctx, a ->
                KibiP2.result {
                    val kinds = (a[0] as CanonValue.Items).items.map(P2::str)
                    val cap = (a[1] as CanonValue.Case).payload?.let(KibiP2::u32)
                    CanonValue.Handle(KibiEvents.open(ctx, kinds, cap, KibiP2.placement(ctx, EVENT_QUEUE)))
                }
            },
            "[method]event-queue.take" to body { ctx, a ->
                KibiP2.result {
                    val max = KibiP2.u32(a[1])
                    if (max < 1) fsError(Errno.INVAL, "max は 1 以上（$max）")
                    val batch = ctx.takeEvents(KibiP2.handle(ctx, a[0], EVENT_QUEUE), max.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    ctx.chargeFuel(batch.events.size.toLong())
                    batchValue(batch)
                }
            },
        )
        val timers = mapOf(
            "[static]timer.create" to body { ctx, a ->
                KibiP2.result {
                    val some = (a[0] as CanonValue.Case).payload as CanonValue.Fields?
                    val queue = some?.let { KibiP2.handle(ctx, it.fields[0], EVENT_QUEUE) }
                    val tag = (some?.fields?.get(1) as CanonValue.I64?)?.value ?: 0
                    CanonValue.Handle(ctx.createTimer(queue, tag, KibiP2.placement(ctx, TIMER)))
                }
            },
            "[method]timer.set-after" to body { ctx, a -> setTimer(ctx, a, absolute = false) },
            "[method]timer.set-at" to body { ctx, a -> setTimer(ctx, a, absolute = true) },
            "[method]timer.cancel" to body { ctx, a ->
                KibiP2.result {
                    ctx.cancelTimer(KibiP2.handle(ctx, a[0], TIMER))
                    null
                }
            },
            "[method]timer.read" to body { ctx, a -> KibiP2.result { CanonValue.I64(ctx.readTimer(KibiP2.handle(ctx, a[0], TIMER))) } },
        )
        val eventsPoll = mapOf("subscribe" to body { ctx, a -> KibiP2.subscribe(ctx, a[0], EVENT_QUEUE) })
        val timersPoll = mapOf("subscribe" to body { ctx, a -> KibiP2.subscribe(ctx, a[0], TIMER) })

        val tty = mapOf(
            "is-tty" to body { ctx, a -> CanonValue.Bool(KibiTty.isTty(ctx, KibiP2.u32(a[0]))) },
            "get-mode" to body { ctx, a -> KibiP2.result { CanonValue.Flags(KibiTty.mode(ctx, KibiP2.u32(a[0]))) } },
            "set-mode" to body { ctx, a ->
                KibiP2.result {
                    KibiTty.setMode(ctx, KibiP2.u32(a[0]), (a[1] as CanonValue.Flags).bits)
                    null
                }
            },
            "get-size" to body { ctx, a ->
                KibiP2.result {
                    val info = KibiTty.size(ctx, KibiP2.u32(a[0]))
                    CanonValue.Fields(listOf(CanonValue.I32(info.size.columns), CanonValue.I32(info.size.rows), CanonValue.I64(info.generation)))
                }
            },
            "get-foreground" to body { ctx, a -> KibiP2.result { CanonValue.I32(KibiTty.foreground(ctx, KibiP2.u32(a[0]))) } },
            "set-foreground" to body { ctx, a ->
                KibiP2.result {
                    KibiTty.setForeground(ctx, KibiP2.u32(a[0]), KibiP2.u32(a[1]))
                    null
                }
            },
        )
        val pipeStreams = mapOf("create" to body { ctx, _ -> KibiP2.result { pipe(ctx) } })

        val mounts = mapOf(
            "mount" to body { ctx, a ->
                KibiP2.result {
                    val source = P2.str(a[0])
                    val point = P2.str(a[1])
                    KibiP2.checkBytes(source, KibiFs.MAX_SOURCE_BYTES, Errno.INVAL, "source")
                    KibiP2.checkBytes(point, VfsPath.MAX_PATH_BYTES, Errno.NAMETOOLONG, "point")
                    KibiFs.mount(ctx, source, point, (a[2] as CanonValue.Bool).value)
                    null
                }
            },
            "unmount" to body { ctx, a ->
                KibiP2.result {
                    val point = P2.str(a[0])
                    KibiP2.checkBytes(point, VfsPath.MAX_PATH_BYTES, Errno.NAMETOOLONG, "point")
                    KibiFs.unmount(ctx, point)
                    null
                }
            },
            "list-mounts" to body { ctx, _ -> CanonValue.Items(KibiFs.listMounts(ctx).map(::mountInfo)) },
            "list-disks" to body { ctx, _ -> CanonValue.Items(KibiFs.listDisks(ctx).map(::diskInfo)) },
        )
        val memory = mapOf(
            "remaining" to body { ctx, _ -> CanonValue.I64(KibiMemory.remaining(ctx)) },
            // ptr は、commit を直接呼んだ core インスタンスの線形メモリの番地（#777。ADR 0777）。メモリがない・core から直接呼ばれていないなら false。
            // u32 の ptr・len はビットのまま、u64 の keep もビットのまま渡す（p1 と同じ。2^63 以上は必ず断る）
            "commit" to HostFunctionBody { call, a ->
                val m = call.callerMemory
                val ptr = (a[0] as CanonValue.I32).value
                val len = (a[1] as CanonValue.I32).value
                val keep = (a[2] as CanonValue.I64).value
                CanonValue.Bool(m != null && KibiMemory.commit(P2.ctx(call), m, ptr, len, keep))
            },
        )
        val clock = mapOf(
            "uptime-ticks" to body { ctx, _ -> CanonValue.I64(KibiClock.uptimeTicks(ctx)) },
            "world-ticks" to body { ctx, _ -> CanonValue.I64(KibiClock.worldTicks(ctx)) },
        )
        val power = mapOf(
            // 戻らない（ProcessKilledException はトラップに書き換えずにそのまま投げる。P2.guard は ResourceLimitException だけを見る）
            "shutdown" to body { ctx, _ -> KibiPower.shutdown(ctx) },
            "reboot" to body { ctx, _ -> CanonValue.err(CanonValue.Case(KibiPower.reboot(ctx), null)) },
        )
        val access = mapOf(
            "get-access" to body { ctx, _ -> KibiP2.result { accessInfo(KibiAccess.getAccess(ctx)) } },
            "request-add" to body { ctx, a -> accessRequest(ctx, a, AccessRequestKind.ADD) },
            "request-remove" to body { ctx, a -> accessRequest(ctx, a, AccessRequestKind.REMOVE) },
            "request-transfer" to body { ctx, a -> accessRequest(ctx, a, AccessRequestKind.TRANSFER) },
        )
        return mapOf(
            EVENTS to events, TIMERS to timers, EVENTS_POLL to eventsPoll, TIMERS_POLL to timersPoll,
            TTY to tty, PIPE_STREAMS to pipeStreams, MOUNTS to mounts, MEMORY to memory, CLOCK to clock, POWER to power, ACCESS to access,
        )
    }

    /** `request-*`。文字列の上限は p1 の lift と同じ（超えたら `invalid`） */
    private fun accessRequest(ctx: ProcessContext, a: List<CanonValue>, kind: AccessRequestKind): CanonValue = KibiP2.result {
        val argument = P2.str(a[0])
        KibiP2.checkBytes(argument, KibiAccess.MAX_ARGUMENT_BYTES, Errno.INVAL, "argument")
        KibiAccess.request(ctx, kind, argument)
        null
    }

    /** `access-info` */
    private fun accessInfo(info: KibiAccess.AccessInfo): CanonValue = CanonValue.Fields(
        listOf(
            CanonValue.Case(info.state.ordinal, null),
            info.owner?.let { CanonValue.some(player(it)) } ?: CanonValue.NONE,
            CanonValue.Items(info.members.map(::player)),
            CanonValue.Items(info.invites.map { CanonValue.str(it) }),
        ),
    )

    private fun player(p: KibiAccess.Player): CanonValue = CanonValue.Fields(listOf(CanonValue.str(p.name), CanonValue.str(p.id)))

    private fun setTimer(ctx: ProcessContext, a: List<CanonValue>, absolute: Boolean): CanonValue = KibiP2.result {
        ctx.setTimer(KibiP2.handle(ctx, a[0], TIMER), P2.u64(a[1]), P2.u64(a[2]), absolute)
        null
    }

    /**
     * パイプを作り、読む端を input-stream、書く端を output-stream で返す（大きさは既定）。表に 2 つ分の空きがなければ、何も作らずに `mfile`
     * （p1 の `FdTable.pipe` と同じ）。
     *
     * パイプの中身は RAM の勘定（`MemoryAccount`）に入れないので、数は p1 と同じ fd の上限（[DEFAULT_HANDLE_LIMIT]）で抑える
     * （ADR 0059・0685）: この表にあるパイプの端の stream が足して [DEFAULT_HANDLE_LIMIT] を超えるなら `mfile`。
     * `HostResourceTable.LIMIT` だけで抑えると、書いてから書く端を drop したパイプを読む端 1 つで保てるので、
     * プロセスあたり p1（約 1 MiB）の 16 倍のバッファが勘定の外に載る。マシン全体の上限はないので `nfile` は返さない
     */
    @Throws(FsException::class)
    private fun pipe(ctx: ProcessContext): CanonValue = ctx.withKernelLock {
        val table = P2.tableLocked(ctx)
        if (HostResourceTable.LIMIT - table.size < 2) fsError(Errno.MFILE, "ホストの resource の数が上限（${HostResourceTable.LIMIT}）")
        val objects = ctx.kernel.objects
        val ends = table.count { it is StreamEntry && it.handle != null && objects[it.objectId] is PipeEnd }
        if (ends + 2 > DEFAULT_HANDLE_LIMIT) fsError(Errno.MFILE, "パイプの端が上限（$DEFAULT_HANDLE_LIMIT）")
        val ids = Pipes.create(ctx.kernel.objects, Pipes.DEFAULT_CAPACITY)
        val read = table.add(StreamEntry(HandleEntry(ids.read, rights = HandleRights.READ), input = true))
        val write = table.add(StreamEntry(HandleEntry(ids.write, rights = HandleRights.WRITE), input = false))
        CanonValue.Fields(listOf(CanonValue.Handle(read), CanonValue.Handle(write)))
    }

    /** `event-batch` */
    private fun batchValue(batch: EventBatch): CanonValue = CanonValue.Fields(
        listOf(
            CanonValue.Items(
                batch.events.map { e ->
                    CanonValue.Fields(listOf(CanonValue.str(e.type), CanonValue.str(e.source), CanonValue.Items(e.values.map(::eventValue))))
                },
            ),
            CanonValue.I64(batch.dropped),
        ),
    )

    /** `event-value`（判別子は宣言の順: integer・float・boolean・text・bytes） */
    private fun eventValue(v: EventValue): CanonValue = when (v) {
        is EventValue.Integer -> CanonValue.Case(0, CanonValue.I64(v.value))
        is EventValue.Float -> CanonValue.Case(1, CanonValue.F64(v.value))
        is EventValue.Boolean -> CanonValue.Case(2, CanonValue.Bool(v.value))
        is EventValue.Text -> CanonValue.Case(3, CanonValue.str(v.value))
        is EventValue.Bytes -> CanonValue.Case(4, CanonValue.Bytes(v.toByteArray()))
    }

    private fun u32Clamp(v: Long): CanonValue = CanonValue.I32(v.coerceIn(0L, 0xffff_ffffL).toInt())

    private fun mountInfo(m: KibiFs.MountInfo): CanonValue {
        val usage = m.usage?.let { u ->
            CanonValue.some(
                CanonValue.Fields(
                    listOf(
                        CanonValue.I64(u.totalBytes),
                        CanonValue.I64(u.usedBytes),
                        CanonValue.I64(u.availableBytes),
                        u.totalEntries?.let { CanonValue.some(u32Clamp(it)) } ?: CanonValue.NONE,
                        u32Clamp(u.usedEntries),
                    ),
                ),
            )
        } ?: CanonValue.NONE
        return CanonValue.Fields(
            listOf(
                CanonValue.str(m.point),
                CanonValue.str(m.source),
                CanonValue.Case(m.kind.ordinal, null),
                CanonValue.Bool(m.readOnly),
                CanonValue.I32(m.id),
                usage,
            ),
        )
    }

    private fun diskInfo(d: KibiFs.DiskInfo): CanonValue = CanonValue.Fields(
        listOf(
            CanonValue.str(d.source),
            CanonValue.I64(d.capacityBytes),
            CanonValue.I32(d.maxEntries),
            d.mountPoint?.let { CanonValue.some(CanonValue.str(it)) } ?: CanonValue.NONE,
        ),
    )
}
