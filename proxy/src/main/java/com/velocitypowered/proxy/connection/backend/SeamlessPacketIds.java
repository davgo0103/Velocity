/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.api.network.ProtocolVersion;
import io.netty.buffer.ByteBuf;

/**
 * Per-protocol-version clientbound packet ids that seamless transfers need but Velocity does not
 * otherwise decode.
 *
 * <ul>
 *   <li>"Spawn Entity" and "Remove Entities" — to track the source server's entities and clear
 *       them at the swap, since a hot-swap reuses the client's level.</li>
 *   <li>"Chunk Batch Finished" — the marker the server sends when it has finished a batch of
 *       initial chunks, used as the commit trigger so the swap happens once the player's
 *       surroundings have arrived (independent of chunk packet sizes).</li>
 * </ul>
 *
 * <p>The client and backend connections always share a protocol version in Velocity, so ids are
 * selected automatically from the connection's version — no per-server configuration is needed.
 * Values are taken from ViaVersion's {@code ClientboundPackets*} enums (verified against the
 * {@code // 0xNN} id comments in those files). When a new Minecraft version is supported, add its
 * ids below; an unknown version returns -1, which the callers treat as "feature unavailable on
 * this version" (falling back to heuristics, or skipping entity cleanup) rather than failing.</p>
 */
public final class SeamlessPacketIds {

  private SeamlessPacketIds() {
  }

  /**
   * The clientbound "Spawn Entity" packet id. Stable at 0x01 for every version that has a
   * configuration phase (1.20.2+), which is the range seamless transfers support.
   *
   * @param version the connection's protocol version
   * @return the packet id, or -1 if unsupported
   */
  public static int addEntityId(ProtocolVersion version) {
    return version.noLessThan(ProtocolVersion.MINECRAFT_1_20_2) ? 0x01 : -1;
  }

  /**
   * The clientbound "Remove Entities" packet id for the given protocol version.
   *
   * @param version the connection's protocol version
   * @return the packet id, or -1 if the version is not in the table
   */
  public static int removeEntitiesId(ProtocolVersion version) {
    return switch (version) {
      case MINECRAFT_1_20_2, MINECRAFT_1_20_3 -> 0x40;
      case MINECRAFT_1_20_5, MINECRAFT_1_21 -> 0x42;
      case MINECRAFT_1_21_2, MINECRAFT_1_21_4 -> 0x47;
      case MINECRAFT_1_21_5, MINECRAFT_1_21_6, MINECRAFT_1_21_7 -> 0x46;
      case MINECRAFT_1_21_9, MINECRAFT_1_21_11 -> 0x4B;
      case MINECRAFT_26_1, MINECRAFT_26_2 -> 0x4D;
      default -> -1;
    };
  }

  /**
   * The clientbound "Chunk Batch Finished" packet id for the given protocol version.
   *
   * @param version the connection's protocol version
   * @return the packet id, or -1 if the version is not in the table
   */
  public static int chunkBatchFinishedId(ProtocolVersion version) {
    return switch (version) {
      case MINECRAFT_1_20_2, MINECRAFT_1_20_3, MINECRAFT_1_20_5,
           MINECRAFT_1_21, MINECRAFT_1_21_2, MINECRAFT_1_21_4 -> 0x0C;
      case MINECRAFT_1_21_5, MINECRAFT_1_21_6, MINECRAFT_1_21_7,
           MINECRAFT_1_21_9, MINECRAFT_1_21_11, MINECRAFT_26_1, MINECRAFT_26_2 -> 0x0B;
      default -> -1;
    };
  }

  /**
   * The clientbound "Respawn" packet id for the given protocol version. Velocity registers
   * RespawnPacket encode-only (decoding disabled), so a backend's Respawn arrives as an opaque
   * buffer and must be recognized by id. Values mirror Velocity's own StateRegistry mappings.
   * A Respawn during a seamless join means a genuine world change: the client rebuilds its
   * level and the "start waiting for chunks" events must NOT be suppressed.
   *
   * @param version the connection's protocol version
   * @return the packet id, or -1 if the version is not in the table
   */
  public static int respawnId(ProtocolVersion version) {
    return switch (version) {
      case MINECRAFT_1_20_2 -> 0x43;
      case MINECRAFT_1_20_3 -> 0x45;
      case MINECRAFT_1_20_5, MINECRAFT_1_21 -> 0x47;
      case MINECRAFT_1_21_2, MINECRAFT_1_21_4 -> 0x4C;
      case MINECRAFT_1_21_5, MINECRAFT_1_21_6, MINECRAFT_1_21_7 -> 0x4B;
      case MINECRAFT_1_21_9, MINECRAFT_1_21_11 -> 0x50;
      case MINECRAFT_26_1, MINECRAFT_26_2 -> 0x52;
      default -> -1;
    };
  }

  /**
   * Whether both entity packet ids are known for the given version, i.e. entity cleanup can run.
   *
   * @param version the connection's protocol version
   * @return true if cleanup is supported on this version
   */
  public static boolean entityCleanupSupported(ProtocolVersion version) {
    return addEntityId(version) >= 0 && removeEntitiesId(version) >= 0;
  }

  /**
   * Fingerprints the "Game Event: start waiting for chunks" packet (event 13, value 0.0f)
   * without needing its version-specific packet id: the payload is exactly [varint packet id]
   * [unsigned byte 13][float 0.0f]. The length, event byte and all-zero float together make
   * accidental matches on other packet types practically impossible.
   *
   * <p>On clients up to 1.21.1, this event unconditionally opens the "loading terrain" screen
   * (vanilla's {@code startWaitingForNewLevel} has no already-in-a-level check — the same flaw
   * kennytv's force-close-loading-screen mod patches client-side), and the screen closes on the
   * next tick when the player's render section is already compiled. Whether a frame renders in
   * between is luck — the intermittent one-frame flash on seamless transfers. Newer clients skip
   * the screen when the level is already loaded. The seamless path therefore drops this event
   * for the whole join sequence of the swapped backend: the shared world is pre-loaded by
   * precondition, so the screen serves no purpose there on any version.</p>
   *
   * @param buf the raw packet, readerIndex at the packet id
   * @return true if this is the level-load-start game event
   */
  public static boolean isLevelLoadStartEvent(ByteBuf buf) {
    final int readable = buf.readableBytes();
    if (readable < 6 || readable > 8) {
      return false;
    }
    final int readerIndex = buf.readerIndex();
    int idLength = 0;
    for (int i = 0; i < 3; i++) {
      idLength++;
      if ((buf.getByte(readerIndex + i) & 0x80) == 0) {
        break;
      }
    }
    if (readable != idLength + 5) {
      return false;
    }
    if ((buf.getByte(readerIndex + idLength) & 0xFF) != 13) {
      return false;
    }
    return buf.getInt(readerIndex + idLength + 1) == 0;
  }

  /**
   * Reads the leading varint (packet id) of a raw packet without consuming it.
   *
   * @param buf the raw packet, readerIndex at the packet id
   * @return the packet id, or -1 if it could not be read within 3 bytes
   */
  public static int peekVarInt(ByteBuf buf) {
    final int readerIndex = buf.readerIndex();
    int result = 0;
    for (int i = 0; i < 3; i++) {
      if (buf.writerIndex() <= readerIndex + i) {
        return -1;
      }
      final byte read = buf.getByte(readerIndex + i);
      result |= (read & 0x7F) << (7 * i);
      if ((read & 0x80) == 0) {
        return result;
      }
    }
    return -1;
  }
}
