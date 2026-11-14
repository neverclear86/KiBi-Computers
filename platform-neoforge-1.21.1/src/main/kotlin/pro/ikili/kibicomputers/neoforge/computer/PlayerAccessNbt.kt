package pro.ikili.kibicomputers.neoforge.computer

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import pro.ikili.kibicomputers.core.access.AccessMember
import pro.ikili.kibicomputers.core.access.PendingInvite
import pro.ikili.kibicomputers.core.access.PlayerAccessRecord

/**
 * 持ち主と権限の記録（[PlayerAccessRecord]。#131、ADR 0131）と、ケースの NBT の複合タグ `PlayerAccess` の変換。
 * 形の規則は core にあり、ここは木の読み書きだけ。UUID は `putUUID`（int[4]）で書く（1.20.1 も同じ API）。
 */
internal object PlayerAccessNbt {
    private const val VERSION = "Version"
    private const val OWNER = "Owner"
    private const val MEMBERS = "Members"
    private const val PENDING = "Pending"
    private const val ID = "Id"
    private const val NAME = "Name"
    private const val CREATED = "Created"

    fun write(record: PlayerAccessRecord): CompoundTag {
        val tag = CompoundTag()
        tag.putInt(VERSION, record.version)
        tag.put(
            OWNER,
            CompoundTag().apply {
                record.ownerId?.let { putUUID(ID, it) }
                record.ownerName?.let { putString(NAME, it) }
            },
        )
        if (record.members.isNotEmpty()) {
            tag.put(MEMBERS, ListTag().apply { record.members.forEach { add(member(it)) } })
        }
        if (record.pending.isNotEmpty()) {
            tag.put(
                PENDING,
                ListTag().apply {
                    record.pending.forEach { add(CompoundTag().apply { putString(NAME, it.name); putLong(CREATED, it.createdMillis) }) }
                },
            )
        }
        return tag
    }

    /**
     * [tag] を読む。版がない・持ち主の UUID がないなどの崩れは、版 0・UUID なしの記録にして、core の判定（locked）に任せる。
     * 形の崩れたメンバー（UUID がない）と保留（名前・時刻がない）は読み飛ばす（権限が減る向きにだけ崩れる）
     */
    fun read(tag: CompoundTag): PlayerAccessRecord {
        val version = if (tag.contains(VERSION, Tag.TAG_INT.toInt())) tag.getInt(VERSION) else 0
        val owner = if (tag.contains(OWNER, Tag.TAG_COMPOUND.toInt())) tag.getCompound(OWNER) else CompoundTag()
        val ownerId = if (owner.hasUUID(ID)) owner.getUUID(ID) else null
        val ownerName = if (owner.contains(NAME, Tag.TAG_STRING.toInt())) owner.getString(NAME) else null
        val members = tag.getList(MEMBERS, Tag.TAG_COMPOUND.toInt()).mapNotNull { e ->
            val m = e as CompoundTag
            if (!m.hasUUID(ID)) null else AccessMember(m.getUUID(ID), m.getString(NAME))
        }
        val pending = tag.getList(PENDING, Tag.TAG_COMPOUND.toInt()).mapNotNull { e ->
            val p = e as CompoundTag
            if (!p.contains(NAME, Tag.TAG_STRING.toInt()) || !p.contains(CREATED, Tag.TAG_LONG.toInt())) null
            else PendingInvite(p.getString(NAME), p.getLong(CREATED))
        }
        return PlayerAccessRecord(version, ownerId, ownerName, members, pending)
    }

    private fun member(m: AccessMember): CompoundTag = CompoundTag().apply {
        putUUID(ID, m.id)
        putString(NAME, m.name)
    }
}
