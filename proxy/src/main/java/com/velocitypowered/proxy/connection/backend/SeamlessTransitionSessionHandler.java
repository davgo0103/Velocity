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

import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.util.ConnectionMessages;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.JoinGamePacket;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import io.netty.buffer.ByteBuf;

/**
 * The PLAY-state counterpart of {@link TransitionSessionHandler} for seamless transfers: instead
 * of forwarding the target server's packets to the client, everything after JoinGame is buffered
 * in the {@link SeamlessSwitchController} until the snapshot is committed. Keep-alives are
 * answered by the proxy so the target server does not time out while buffering.
 */
public class SeamlessTransitionSessionHandler implements MinecraftSessionHandler {

  private final VelocityServerConnection serverConn;
  private final SeamlessSwitchController controller;
  private final BungeeCordMessageResponder bungeecordMessageResponder;

  SeamlessTransitionSessionHandler(VelocityServer server, VelocityServerConnection serverConn,
      SeamlessSwitchController controller) {
    this.serverConn = serverConn;
    this.controller = controller;
    this.bungeecordMessageResponder = new BungeeCordMessageResponder(server,
        serverConn.getPlayer());
  }

  @Override
  public boolean beforeHandle() {
    if (!serverConn.isActive()) {
      // Obsolete connection
      serverConn.disconnect();
      return true;
    }
    return false;
  }

  @Override
  public boolean handle(KeepAlivePacket packet) {
    // The client does not see the target server yet, so the proxy answers on its behalf.
    serverConn.ensureConnected().write(packet);
    return true;
  }

  @Override
  public boolean handle(JoinGamePacket packet) {
    controller.onTargetJoinGame(packet);
    return true;
  }

  @Override
  public boolean handle(DisconnectPacket packet) {
    serverConn.disconnect();
    controller.abort(ConnectionRequestResults.forDisconnect(packet, serverConn.getServer()), null);
    return true;
  }

  @Override
  public boolean handle(PluginMessagePacket packet) {
    if (bungeecordMessageResponder.process(packet)) {
      return true;
    }
    controller.bufferPacket(packet);
    return true;
  }

  @Override
  public void handleGeneric(MinecraftPacket packet) {
    controller.bufferPacket(packet);
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    controller.bufferUnknown(buf);
  }

  @Override
  public void exception(Throwable throwable) {
    controller.abort(null, throwable);
  }

  @Override
  public void disconnected() {
    controller.abort(ConnectionRequestResults.forDisconnect(
        ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR, serverConn.getServer()), null);
  }
}
