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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SeamlessPacketIdsTest {

  /**
   * Fails when Velocity gains a Minecraft version that the seamless packet table does not cover.
   * Regenerate the table with scripts/seamless-packet-ids.py (see SEAMLESS.md); until then such
   * players would silently fall back to the regular switch.
   */
  @Test
  void everySupportedVersionHasPacketIds() {
    for (ProtocolVersion version : ProtocolVersion.values()) {
      if (version.noLessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
        assertTrue(SeamlessPacketIds.isSupported(version),
            "SeamlessPacketIds has no table for " + version
                + " — regenerate it with scripts/seamless-packet-ids.py");
      }
    }
  }

  @Test
  void tableMatchesKnownIds() {
    // Spot checks against values verified on real clients before the table was generated.
    final SeamlessPacketIds.Table v1212 = SeamlessPacketIds.forVersion(
        ProtocolVersion.MINECRAFT_1_21_2);
    assertNotNull(v1212);
    assertEquals(0x47, v1212.removeEntities());
    assertEquals(0x0C, v1212.chunkBatchFinished());
    assertEquals(0x4C, v1212.respawn());

    final SeamlessPacketIds.Table v263 = SeamlessPacketIds.forVersion(
        ProtocolVersion.MINECRAFT_26_3);
    assertNotNull(v263);
    assertEquals(0x4E, v263.removeEntities());
    assertEquals(0x0B, v263.chunkBatchFinished());
    assertEquals(0x54, v263.respawn());
    assertEquals(SeamlessPacketIds.LAYOUT_LEADING_INT, v263.clientboundLayout(0x22)); // event
    assertEquals(SeamlessPacketIds.LAYOUT_VARINT_LIST, v263.clientboundLayout(0x4E));
    assertEquals(SeamlessPacketIds.LAYOUT_LEADING_VARINT, v263.serverboundLayout(0x1A)); // use
  }

  @Test
  void recognizesLevelLoadStartEvent() {
    final SeamlessPacketIds.Table ids = SeamlessPacketIds.forVersion(
        ProtocolVersion.MINECRAFT_1_21);
    assertNotNull(ids);
    assertTrue(SeamlessPacketIds.isLevelLoadStartEvent(
        SeamlessPacketIds.gameEvent(ids, 13, 0f, Unpooled.buffer()), ids));
    assertFalse(SeamlessPacketIds.isLevelLoadStartEvent(
        SeamlessPacketIds.gameEvent(ids, 7, 0f, Unpooled.buffer()), ids));
    assertFalse(SeamlessPacketIds.isLevelLoadStartEvent(
        SeamlessPacketIds.gameEvent(ids, 13, 1f, Unpooled.buffer()), ids));
  }

  @Test
  void recognizesSignedPlayerChat() {
    for (ProtocolVersion version : new ProtocolVersion[] {
        ProtocolVersion.MINECRAFT_1_21_4, ProtocolVersion.MINECRAFT_1_21_5}) {
      final SeamlessPacketIds.Table ids = SeamlessPacketIds.forVersion(version);
      assertNotNull(ids);
      assertTrue(SeamlessPacketIds.isSignedPlayerChat(playerChat(ids, version, true), ids,
          version), version.toString());
      assertFalse(SeamlessPacketIds.isSignedPlayerChat(playerChat(ids, version, false), ids,
          version), version.toString());
    }
  }

  private static ByteBuf playerChat(SeamlessPacketIds.Table ids, ProtocolVersion version,
      boolean signed) {
    final ByteBuf buf = Unpooled.buffer();
    ProtocolUtils.writeVarInt(buf, ids.playerChat());
    if (version.noLessThan(ProtocolVersion.MINECRAFT_1_21_5)) {
      ProtocolUtils.writeVarInt(buf, 300); // global index (multi-byte on purpose)
    }
    ProtocolUtils.writeUuid(buf, UUID.randomUUID());
    ProtocolUtils.writeVarInt(buf, 5); // index
    buf.writeBoolean(signed);
    if (signed) {
      buf.writeZero(256);
    }
    ProtocolUtils.writeString(buf, "hello");
    return buf;
  }
}
