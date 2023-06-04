package dev.luna5ama.trollhack.module.modules.combat

import dev.luna5ama.trollhack.event.events.PacketEvent
import dev.luna5ama.trollhack.event.events.TickEvent
import dev.luna5ama.trollhack.event.events.WorldEvent
import dev.luna5ama.trollhack.event.safeListener
import dev.luna5ama.trollhack.event.safeParallelListener
import dev.luna5ama.trollhack.gui.hudgui.elements.client.Notification
import dev.luna5ama.trollhack.manager.managers.EntityManager
import dev.luna5ama.trollhack.manager.managers.HotbarSwitchManager.serverSideItem
import dev.luna5ama.trollhack.module.Category
import dev.luna5ama.trollhack.module.Module
import dev.luna5ama.trollhack.module.modules.player.PacketMine
import dev.luna5ama.trollhack.util.EntityUtils.isFriend
import dev.luna5ama.trollhack.util.EntityUtils.isSelf
import dev.luna5ama.trollhack.util.extension.sq
import dev.luna5ama.trollhack.util.items.block
import dev.luna5ama.trollhack.util.math.vector.distanceSqTo
import dev.luna5ama.trollhack.util.runIf
import dev.luna5ama.trollhack.util.world.getBlock
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet
import net.minecraft.block.BlockShulkerBox
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.tileentity.TileEntityShulkerBox
import net.minecraft.util.math.BlockPos

internal object AntiRegear : Module(
    name = "Anti Regear",
    description = "Prevents enemy from regearing using shulkers",
    category = Category.COMBAT,
    modulePriority = 100
) {
    private val ignoreSelfPlaced by setting("Ignore Self Placed", true)
    private val selfRange by setting("Self Range", 1.0f, 0.0f..10.0f, 0.1f)
    private val friendRange by setting("Friend Range", 1.0f, 0.0f..10.0f, 0.1f)
    private val otherPlayerRange by setting("Other Player Range", 6.0f, 0.0f..10.0f, 0.1f)
    private val mineRange by setting("Mine Range", 5.5f, 1.0f..10.0f, 0.1f)
    private val silentNotification by setting("Silent Notification", false)

    private val selfPlaced = ObjectLinkedOpenHashSet<BlockPos>()
    private val mineQueue = ObjectLinkedOpenHashSet<BlockPos>()

    init {
        onDisable {
            synchronized(selfPlaced) {
                selfPlaced.clear()
            }
            mineQueue.clear()
        }

        safeListener<PacketEvent.PostSend> {
            if (it.packet !is CPacketPlayerTryUseItemOnBlock) return@safeListener
            if (player.serverSideItem.item.block !is BlockShulkerBox) return@safeListener

            addSelfPlaced(it.packet.pos.offset(it.packet.direction))
        }

        safeParallelListener<TickEvent.Post> {
            if (PacketMine.isDisabled) {
                if (!silentNotification) Notification.send("You must have PacketMine enabled for AntiRegear to work")
                return@safeParallelListener
            }

            synchronized(selfPlaced) {
                selfPlaced.removeIf {
                    world.getBlock(it) !is BlockShulkerBox
                }
            }

            val mineRangeSq = mineRange.sq
            world.loadedTileEntityList.asSequence()
                .filterIsInstance<TileEntityShulkerBox>()
                .filter { player.distanceSqTo(it.pos) <= mineRangeSq }
                .filter { otherPlayerNearBy(it.pos) }
                .runIf(ignoreSelfPlaced) {
                    filterNot { selfPlaced.contains(it.pos) }
                }
                .mapTo(mineQueue) { it.pos }

            var pos: BlockPos? = null

            while (!mineQueue.isEmpty()) {
                pos = mineQueue.first()
                if (player.distanceSqTo(pos) > mineRangeSq || world.getBlock(pos) !is BlockShulkerBox) {
                    mineQueue.removeFirst()
                } else {
                    break
                }
            }


            if (pos == null) {
                PacketMine.reset(AntiRegear)
            } else {
                PacketMine.mineBlock(AntiRegear, pos, AntiRegear.modulePriority, true)
            }
        }

        safeListener<WorldEvent.ClientBlockUpdate> { event ->
            val playerDistance = player.distanceSqTo(event.pos)
            if (playerDistance > mineRange.sq) return@safeListener

            if (event.newState.block !is BlockShulkerBox) {
                synchronized(selfPlaced) {
                    selfPlaced.remove(event.pos)
                }
                mineQueue.remove(event.pos)
            } else {
                if (ignoreSelfPlaced && selfPlaced.contains(event.pos)) return@safeListener
                if (playerDistance <= selfRange.sq) return@safeListener
                if (mineQueue.contains(event.pos)) return@safeListener
                if (!otherPlayerNearBy(event.pos)) return@safeListener

                mineQueue.add(event.pos)
            }
        }
    }

    private fun otherPlayerNearBy(
        pos: BlockPos
    ): Boolean {
        val otherPlayerRangeSq = otherPlayerRange.sq
        val friendRangeSq = friendRange.sq
        val playerSequence = EntityManager.players.asSequence().filterNot { it.isSelf }

        val noFriendInRange = playerSequence.filter { it.isFriend }
            .filter { it.distanceSqTo(pos) <= friendRangeSq }
            .none()

        val othersInRange = playerSequence
            .filterNot { it.isFriend }
            .filter { it.distanceSqTo(pos) <= otherPlayerRangeSq }
            .any()

        return noFriendInRange && othersInRange
    }

    private fun addSelfPlaced(pos: BlockPos) {
        synchronized(selfPlaced) {
            if (selfPlaced.size > 10) selfPlaced.removeLast()
            selfPlaced.addAndMoveToFirst(pos)
        }
    }
}