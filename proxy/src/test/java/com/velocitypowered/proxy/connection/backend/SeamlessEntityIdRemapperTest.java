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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class SeamlessEntityIdRemapperTest {

  private static final int CLIENT_ID = 7;
  // Multi-byte varint, so rewrites change the packet length.
  private static final int BACKEND_ID = 300;

  // 26.3 ids (see SeamlessPacketIds).
  private static final int SET_ENTITY_DATA = 0x65;
  private static final int ENTITY_EVENT = 0x22;
  private static final int SET_PASSENGERS = 0x6D;
  private static final int REMOVE_ENTITIES = 0x4E;
  private static final int SET_HEALTH = 0x6A;
  private static final int INTERACT = 0x1A;

  private final SeamlessPacketIds.Table ids =
      SeamlessPacketIds.forVersion(ProtocolVersion.MINECRAFT_26_3);
  private final SeamlessEntityIdRemapper remapper =
      new SeamlessEntityIdRemapper(CLIENT_ID, BACKEND_ID, ids);

  @Test
  void noRemapperWhenIdsMatch() {
    assertNull(SeamlessEntityIdRemapper.create(5, 5, ids));
    assertNotNull(SeamlessEntityIdRemapper.create(5, 6, ids));
  }

  @Test
  void translatesThePlayersOwnId() {
    final ByteBuf in = varInts(SET_ENTITY_DATA, BACKEND_ID, 0xFF);
    final ByteBuf out = remapper.rewriteClientbound(in);
    assertNotSame(in, out);
    assertEquals(SET_ENTITY_DATA, ProtocolUtils.readVarInt(out));
    assertEquals(CLIENT_ID, ProtocolUtils.readVarInt(out));
    assertEquals(0xFF, ProtocolUtils.readVarInt(out)); // the rest is copied
    assertEquals(0, out.readableBytes());
    assertEquals(0, in.readerIndex()); // the input is not consumed
  }

  @Test
  void movesCollidingBackendEntityOutOfTheWay() {
    final ByteBuf out = remapper.rewriteClientbound(varInts(SET_ENTITY_DATA, CLIENT_ID));
    ProtocolUtils.readVarInt(out);
    assertEquals(BACKEND_ID, ProtocolUtils.readVarInt(out));
  }

  @Test
  void leavesOtherPacketsUntouched() {
    final ByteBuf otherEntity = varInts(SET_ENTITY_DATA, 42);
    assertSame(otherEntity, remapper.rewriteClientbound(otherEntity));
    final ByteBuf notAnEntityPacket = varInts(SET_HEALTH, BACKEND_ID);
    assertSame(notAnEntityPacket, remapper.rewriteClientbound(notAnEntityPacket));
  }

  @Test
  void translatesIntEntityIds() {
    final ByteBuf in = Unpooled.buffer();
    ProtocolUtils.writeVarInt(in, ENTITY_EVENT);
    in.writeInt(BACKEND_ID);
    in.writeByte(28); // operator permission level 4
    final ByteBuf out = remapper.rewriteClientbound(in);
    ProtocolUtils.readVarInt(out);
    assertEquals(CLIENT_ID, out.readInt());
    assertEquals(28, out.readByte());
  }

  @Test
  void translatesEntityIdLists() {
    final ByteBuf passengers = remapper.rewriteClientbound(
        varInts(SET_PASSENGERS, 42, 2, 43, BACKEND_ID));
    ProtocolUtils.readVarInt(passengers);
    assertEquals(42, ProtocolUtils.readVarInt(passengers));
    assertEquals(2, ProtocolUtils.readVarInt(passengers));
    assertEquals(43, ProtocolUtils.readVarInt(passengers));
    assertEquals(CLIENT_ID, ProtocolUtils.readVarInt(passengers));

    final ByteBuf remove = remapper.rewriteClientbound(varInts(REMOVE_ENTITIES, 1, CLIENT_ID));
    ProtocolUtils.readVarInt(remove);
    assertEquals(1, ProtocolUtils.readVarInt(remove));
    assertEquals(BACKEND_ID, ProtocolUtils.readVarInt(remove));
  }

  @Test
  void translatesServerboundBackToTheBackendsIds() {
    final ByteBuf out = remapper.rewriteServerbound(varInts(INTERACT, CLIENT_ID, 0));
    ProtocolUtils.readVarInt(out);
    assertEquals(BACKEND_ID, ProtocolUtils.readVarInt(out));
  }

  @Test
  void forwardsMalformedPacketsUntouched() {
    final ByteBuf truncated = varInts(REMOVE_ENTITIES, 50, BACKEND_ID);
    assertSame(truncated, remapper.rewriteClientbound(truncated));
  }

  private static ByteBuf varInts(int... values) {
    final ByteBuf buf = Unpooled.buffer();
    for (int value : values) {
      ProtocolUtils.writeVarInt(buf, value);
    }
    return buf;
  }
}
