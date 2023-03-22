package me.luna.trollhack.module.modules.movement

import io.netty.util.internal.ConcurrentSet
import me.luna.trollhack.event.SafeClientEvent
import me.luna.trollhack.event.events.PacketEvent
import me.luna.trollhack.event.events.player.PlayerMoveEvent
import me.luna.trollhack.event.safeListener
import me.luna.trollhack.module.Category
import me.luna.trollhack.module.Module
import me.luna.trollhack.util.MovementUtils
import me.luna.trollhack.util.MovementUtils.calcMoveYaw
import net.minecraft.network.play.client.CPacketConfirmTeleport
import net.minecraft.network.play.client.CPacketPlayer
import net.minecraft.network.play.server.SPacketCloseWindow
import net.minecraft.network.play.server.SPacketPlayerPosLook
import kotlin.math.*
import kotlin.random.Random

internal object PacketFly : Module(
    name = "PacketFly",
    description = "Experimental!",
    category = Category.MOVEMENT,
    modulePriority = 9999
) {
    private val page by setting("Page", Page.MOVEMENT)

    private val sprintFastMode by setting("Sprint Fast Mode", true, { page == Page.MOVEMENT })
    private val upSpeed by setting("Up Speed", 0.062, 0.0..1.0, 0.01, { page == Page.MOVEMENT })
    private val downSpeed by setting("Down Speed", 0.062, 0.0..1.0, 0.01, { page == Page.MOVEMENT })
    private val speed by setting("Speed", 0.062, 0.0..1.0, 0.01, { page == Page.MOVEMENT })
    private val confirmTeleportMove by setting("Confirm Teleport Move", true, { page == Page.MOVEMENT })

    private val spoofX = SpoofSetting(
        axis = "X",
        mode = SpoofMode.RANDOM,
        randomMin = -10.0,
        randomMax = 10.0,
    )
    private val spoofY = SpoofSetting(
        axis = "Y",
        mode = SpoofMode.JITTER,
        constant = 1337.69,
        randomMin = -100.0,
        randomMax = -80.0,
        jitterMin = 80.0,
        jitterMax = 100.0,
    )
    private val spoofZ = SpoofSetting(
        axis = "Z",
        mode = SpoofMode.RANDOM,
        randomMin = -10.0,
        randomMax = 10.0,
    )

    private val spoofGroundMode by setting("Spoof Ground Mode", GroundMode.OFF_GROUND, { page == Page.SPOOF })

    private val maxServerIgnore by setting("Max Server Ignores", 2, 0..10, 1, { page == Page.SERVER_PACKET })
    private val forceClientPosition by setting("Force Client Position", true, { page == Page.SERVER_PACKET })

    private enum class Page {
        MOVEMENT, SPOOF, SERVER_PACKET
    }

    private enum class SpoofMode {
        CONSTANT,
        RANDOM,
        JITTER,
    }

    private enum class GroundMode {
        PLAYER_STATE,
        ON_GROUND,
        OFF_GROUND,
    }

    private class SpoofSetting(
        axis: String,
        mode: SpoofMode = SpoofMode.CONSTANT,
        constant: Double = 0.0,
        randomMin: Double = 0.0,
        randomMax: Double = 0.0,
        jitterMin: Double = 0.0,
        jitterMax: Double = 0.0,
    ) {
        var mode by setting("$axis Mode", mode, { page == Page.SPOOF })

        var constant by setting(
            "$axis Constant",
            constant,
            -100000.0..100000.0,
            1.0,
            { page == Page.SPOOF && mode == SpoofMode.CONSTANT }
        )

        var randomMin: Double by setting(
            "$axis Random Min",
            randomMin,
            -100000.0..100000.0,
            1.0,
            { page == Page.SPOOF && (mode == SpoofMode.RANDOM || mode == SpoofMode.JITTER) },
            consumer = { _, input -> min(input, randomMax) }
        )

        var randomMax: Double by setting(
            "$axis Random Max",
            randomMax,
            -100000.0..100000.0,
            1.0,
            { page == Page.SPOOF && (mode == SpoofMode.RANDOM || mode == SpoofMode.JITTER) },
            consumer = { _, input -> max(input, randomMin) }
        )
        var jitterMin: Double by setting(
            "$axis Jitter Min",
            jitterMin,
            -100000.0..100000.0,
            1.0,
            { page == Page.SPOOF && mode == SpoofMode.JITTER },
            consumer = { _, input -> min(input, jitterMax) })

        var jitterMax: Double by setting(
            "$axis Jitter Max",
            jitterMax,
            -100000.0..100000.0,
            1.0,
            { page == Page.SPOOF && mode == SpoofMode.JITTER },
            consumer = { _, input -> max(input, jitterMin) }
        )

        fun offset(event: SafeClientEvent): Double {
            event {
                when (mode) {
                    SpoofMode.CONSTANT -> {
                        return constant
                    }
                    SpoofMode.RANDOM -> {
                        return safeRandom(randomMin, randomMax)
                    }
                    SpoofMode.JITTER -> {
                        return if (Random.nextBoolean()) {
                            safeRandom(randomMin, randomMax)
                        } else {
                            safeRandom(jitterMin, jitterMax)
                        }
                    }
                }
            }
        }

        private fun safeRandom(min: Double, max: Double): Double {
            return if (abs(min - max) < 0.0001) 0.0 else Random.nextDouble(min, max)
        }
    }

    private val packetSet = ConcurrentSet<CPacketPlayer>()
    private var teleportID = 0
    private var serverIgnores = 0
    private var packetFlyingTicks = 0

    override fun isActive(): Boolean {
        return isEnabled && packetFlyingTicks > 0
    }

    init {
        onDisable {
            packetSet.clear()
            teleportID = 0
            serverIgnores = 0
            packetFlyingTicks = 0
        }

        safeListener<PacketEvent.Send> {
            if (packetFlyingTicks <= 0) return@safeListener
            if (player.ticksExisted < 10) return@safeListener

            when (it.packet) {
                is CPacketPlayer -> {
                    if (!packetSet.remove(it.packet)) {
                        it.cancel()
                    }
                }
            }
        }

        safeListener<PacketEvent.Receive> {
            if (packetFlyingTicks <= 0) return@safeListener
            if (player.ticksExisted < 10) return@safeListener

            when (it.packet) {
                is SPacketCloseWindow -> {
                    it.cancel()
                }
                is SPacketPlayerPosLook -> {
                    it.cancel()
                    var x = it.packet.x
                    var y = it.packet.y
                    var z = it.packet.z
                    var yaw = it.packet.yaw
                    var pitch = it.packet.pitch

                    if (it.packet.flags.contains(SPacketPlayerPosLook.EnumFlags.X)) {
                        x += player.posX
                    }

                    if (it.packet.flags.contains(SPacketPlayerPosLook.EnumFlags.Y)) {
                        y += player.posY
                    }

                    if (it.packet.flags.contains(SPacketPlayerPosLook.EnumFlags.Z)) {
                        z += player.posZ
                    }

                    if (it.packet.flags.contains(SPacketPlayerPosLook.EnumFlags.X_ROT)) {
                        pitch += player.rotationPitch
                    }

                    if (it.packet.flags.contains(SPacketPlayerPosLook.EnumFlags.Y_ROT)) {
                        yaw += player.rotationYaw
                    }

                    if (++serverIgnores > maxServerIgnore) {
                        player.setPosition(x, y, z)
                        serverIgnores = 0
                    }

                    teleportID = it.packet.teleportId
                    connection.sendPacket(CPacketConfirmTeleport(it.packet.teleportId))
                    if (forceClientPosition) {
                        sendPlayerPacket(
                            CPacketPlayer.PositionRotation(
                                player.posX,
                                player.entityBoundingBox.minY,
                                player.posZ,
                                player.rotationYaw,
                                player.rotationPitch,
                                false
                            )
                        )
                    } else {
                        sendPlayerPacket(
                            CPacketPlayer.PositionRotation(
                                x,
                                y,
                                z,
                                yaw,
                                pitch,
                                false
                            )
                        )
                    }
                }
            }
        }

        safeListener<PlayerMoveEvent.Pre> {
            updateSmartToggle()

            if (packetFlyingTicks-- <= 0) return@safeListener
            if (player.ticksExisted < 10) return@safeListener

            it.x = 0.0
            it.y = 0.0
            it.z = 0.0
            it.cancel()

            player.motionY = -0.01

            var motionX = 0.0
            var motionY = 0.0
            var motionZ = 0.0

            if (player.movementInput.jump xor player.movementInput.sneak) {
                motionY = if (player.movementInput.jump) upSpeed else -downSpeed
            } else if (MovementUtils.isInputting) {
                val yaw = calcMoveYaw()
                motionX -= sin(yaw) * speed
                motionZ += cos(yaw) * speed
            }

            player.setPosition(
                player.posX + motionX,
                player.posY + motionY,
                player.posZ + motionZ
            )

            sendPlayerPacket(
                CPacketPlayer.PositionRotation(
                    player.posX,
                    player.posY,
                    player.posZ,
                    player.rotationYaw,
                    player.rotationPitch,
                    player.onGround
                )
            )

            val spoofOnGround = when (spoofGroundMode) {
                GroundMode.PLAYER_STATE -> player.onGround
                GroundMode.ON_GROUND -> true
                GroundMode.OFF_GROUND -> false
            }

            sendPlayerPacket(
                CPacketPlayer.Position(
                    player.posX + spoofX.offset(this),
                    player.posY + spoofY.offset(this),
                    player.posZ + spoofZ.offset(this),
                    spoofOnGround
                )
            )

            if (confirmTeleportMove) {
                connection.sendPacket(CPacketConfirmTeleport(++teleportID))
            }
        }
    }

    private fun SafeClientEvent.updateSmartToggle() {
        if (!sprintFastMode || !mc.gameSettings.keyBindSprint.isKeyDown) {
            packetFlyingTicks = 5
        } else {
            if ((player.movementInput.jump && !player.onGround) xor player.movementInput.sneak) {
                packetFlyingTicks = 2
                return
            }

            if (MovementUtils.isInputting && player.collidedHorizontally && isPhasing()) {
                packetFlyingTicks = 5
                return
            }
        }
    }

    private fun SafeClientEvent.isPhasing(): Boolean {
        val yaw = calcMoveYaw()
        val box = player.entityBoundingBox.grow(-0.001, -0.001, -0.001)
        val nextBox = box.offset(-sin(yaw) * 0.05, 0.0, cos(yaw) * 0.05)

        val colliedBoxList = world.getCollisionBoxes(null, nextBox)
        colliedBoxList.removeAll(world.getCollisionBoxes(null, box))
        return colliedBoxList.isNotEmpty()
    }

    private fun SafeClientEvent.sendPlayerPacket(packet: CPacketPlayer) {
        packetSet.add(packet)
        connection.sendPacket(packet)
    }
}