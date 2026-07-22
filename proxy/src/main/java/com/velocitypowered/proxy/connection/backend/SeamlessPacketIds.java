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
   * Whether both entity packet ids are known for the given version, i.e. entity cleanup can run.
   *
   * @param version the connection's protocol version
   * @return true if cleanup is supported on this version
   */
  public static boolean entityCleanupSupported(ProtocolVersion version) {
    return addEntityId(version) >= 0 && removeEntitiesId(version) >= 0;
  }
}
