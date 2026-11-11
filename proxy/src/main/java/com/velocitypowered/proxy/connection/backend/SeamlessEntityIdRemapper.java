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

import static com.velocitypowered.proxy.connection.backend.SeamlessPacketIds.LAYOUT_LEADING_INT;
import static com.velocitypowered.proxy.connection.backend.SeamlessPacketIds.LAYOUT_LEADING_VARINT;
import static com.velocitypowered.proxy.connection.backend.SeamlessPacketIds.LAYOUT_NONE;
import static com.velocitypowered.proxy.connection.backend.SeamlessPacketIds.LAYOUT_PASSENGERS;
import static com.velocitypowered.proxy.connection.backend.SeamlessPacketIds.LAYOUT_TWO_INTS;
import static com.velocitypowered.proxy.connection.backend.SeamlessPacketIds.LAYOUT_TWO_VARINTS;
import static com.velocitypowered.proxy.connection.backend.SeamlessPacketIds.LAYOUT_VARINT_LIST;

import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Translates the player's own entity id after a seamless transfer.
 *
 * <p>A hot-swap sends the client no JoinGame, so the client keeps the entity id it was given by
 * the first server, while the new backend refers to the player by its own id. Packets about the
 * player (entity events such as the operator permission level, attributes, mob effects,
 * knockback, the death screen, riding) would otherwise be ignored by the client. This remapper
 * swaps the two ids in both directions: the backend's id for the player becomes the client's,
 * and a backend entity that happens to use the client's id is moved onto the backend's player id
 * (which the backend never uses for anything the client sees), so it cannot replace the player
 * on the client.</p>
 *
 * <p>Only the packets listed in {@link SeamlessPacketIds} are translated; their layouts are fixed
 * prefixes, so a rewrite touches a few bytes and copies the rest. Packets that do not mention
 * either id are returned unchanged without copying.</p>
 */
public final class SeamlessEntityIdRemapper {

  private final int clientId;
  private final int backendId;
  private final SeamlessPacketIds.Table table;

  /**
   * Creates a remapper.
   *
   * @param clientId  the entity id the client believes the player has
   * @param backendId the entity id the current backend gave the player
   * @param table     the connection's packet id table
   */
  public SeamlessEntityIdRemapper(int clientId, int backendId, SeamlessPacketIds.Table table) {
    this.clientId = clientId;
    this.backendId = backendId;
    this.table = table;
  }

  /**
   * Creates a remapper, or returns null if the two ids are equal (nothing to translate).
   *
   * @param clientId  the entity id the client believes the player has
   * @param backendId the entity id the current backend gave the player
   * @param table     the connection's packet id table, or null if unsupported
   * @return the remapper, or null
   */
  public static @Nullable SeamlessEntityIdRemapper create(int clientId, int backendId,
      SeamlessPacketIds.@Nullable Table table) {
    if (clientId == backendId || table == null) {
      return null;
    }
    return new SeamlessEntityIdRemapper(clientId, backendId, table);
  }

  public int clientId() {
    return clientId;
  }

  public int backendId() {
    return backendId;
  }

  /**
   * Maps an entity id between the backend's and the client's view (the mapping is its own
   * inverse).
   *
   * @param id the entity id
   * @return the translated id
   */
  public int map(int id) {
    if (id == backendId) {
      return clientId;
    }
    if (id == clientId) {
      return backendId;
    }
    return id;
  }

  /**
   * Translates a raw backend-to-client packet.
   *
   * @param buf the raw packet, readerIndex at the packet id; not released
   * @return {@code buf} itself if unchanged, otherwise a new buffer owned by the caller
   */
  public ByteBuf rewriteClientbound(ByteBuf buf) {
    return rewrite(buf, table.clientboundLayout(SeamlessPacketIds.peekVarInt(buf)));
  }

  /**
   * Translates a raw client-to-backend packet.
   *
   * @param buf the raw packet, readerIndex at the packet id; not released
   * @return {@code buf} itself if unchanged, otherwise a new buffer owned by the caller
   */
  public ByteBuf rewriteServerbound(ByteBuf buf) {
    return rewrite(buf, table.serverboundLayout(SeamlessPacketIds.peekVarInt(buf)));
  }

  private ByteBuf rewrite(ByteBuf buf, byte layout) {
    if (layout == LAYOUT_NONE) {
      return buf;
    }
    ByteBuf out = null;
    try {
      final ByteBuf probe = buf.duplicate();
      ProtocolUtils.readVarInt(probe);
      if (!transform(probe, null, layout)) {
        return buf;
      }
      final ByteBuf in = buf.duplicate();
      out = buf.alloc().buffer(buf.readableBytes() + 8);
      ProtocolUtils.writeVarInt(out, ProtocolUtils.readVarInt(in));
      transform(in, out, layout);
      out.writeBytes(in);
      return out;
    } catch (RuntimeException e) {
      // Malformed packet: forward it untouched, the receiver will judge it.
      if (out != null) {
        out.release();
      }
      return buf;
    }
  }

  /**
   * Reads the entity-id fields of a packet body and, when {@code out} is non-null, writes them
   * translated. Non-short-circuit {@code |} keeps every field consumed.
   */
  private boolean transform(ByteBuf in, @Nullable ByteBuf out, byte layout) {
    return switch (layout) {
      case LAYOUT_LEADING_VARINT -> varInt(in, out);
      case LAYOUT_LEADING_INT -> intField(in, out);
      case LAYOUT_TWO_INTS -> intField(in, out) | intField(in, out);
      case LAYOUT_TWO_VARINTS -> varInt(in, out) | varInt(in, out);
      case LAYOUT_PASSENGERS -> varInt(in, out) | varIntList(in, out);
      case LAYOUT_VARINT_LIST -> varIntList(in, out);
      default -> false;
    };
  }

  private boolean varInt(ByteBuf in, @Nullable ByteBuf out) {
    final int id = ProtocolUtils.readVarInt(in);
    final int mapped = map(id);
    if (out != null) {
      ProtocolUtils.writeVarInt(out, mapped);
    }
    return mapped != id;
  }

  private boolean intField(ByteBuf in, @Nullable ByteBuf out) {
    final int id = in.readInt();
    final int mapped = map(id);
    if (out != null) {
      out.writeInt(mapped);
    }
    return mapped != id;
  }

  private boolean varIntList(ByteBuf in, @Nullable ByteBuf out) {
    final int count = ProtocolUtils.readVarInt(in);
    if (count < 0 || count > in.readableBytes()) {
      throw new IllegalArgumentException("bad entity id list length " + count);
    }
    if (out != null) {
      ProtocolUtils.writeVarInt(out, count);
    }
    boolean changed = false;
    for (int i = 0; i < count; i++) {
      changed |= varInt(in, out);
    }
    return changed;
  }
}
