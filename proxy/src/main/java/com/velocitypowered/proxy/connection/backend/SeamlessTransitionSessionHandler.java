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
  public void activated() {
    // Route every decoded packet through intercept() below instead of its own handle(), so a
    // decoded packet's per-packet logic (including third-party plugin hooks that assume the
    // backend handler is a BackendPlaySessionHandler) never runs while we are buffering.
    serverConn.ensureConnected().interceptingPackets = true;
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
  public boolean intercept(MinecraftPacket packet) {
    if (packet instanceof JoinGamePacket joinGame) {
      controller.onTargetJoinGame(joinGame);
      return true;
    }
    if (packet instanceof KeepAlivePacket keepAlive) {
      // The client does not see the target server yet, so the proxy answers on its behalf.
      serverConn.ensureConnected().write(keepAlive);
      return true;
    }
    if (packet instanceof DisconnectPacket disconnect) {
      serverConn.disconnect();
      controller.abort(
          ConnectionRequestResults.forDisconnect(disconnect, serverConn.getServer()), null);
      return true;
    }
    if (packet instanceof PluginMessagePacket pluginMessage
        && bungeecordMessageResponder.process(pluginMessage)) {
      return true;
    }
    controller.bufferPacket(packet);
    return true;
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
