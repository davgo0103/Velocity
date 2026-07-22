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

import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.client.ClientPlaySessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.util.ConnectionMessages;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import com.velocitypowered.proxy.protocol.packet.ClientboundCookieRequestPacket;
import com.velocitypowered.proxy.protocol.packet.ClientboundStoreCookiePacket;
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.ResourcePackRequestPacket;
import com.velocitypowered.proxy.protocol.packet.ResourcePackResponsePacket;
import com.velocitypowered.proxy.protocol.packet.config.CodeOfConductPacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket;
import com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket;
import com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Answers the target server's configuration phase on behalf of the client during a seamless
 * transfer. The client never leaves the PLAY state on the source server, so registry data, tags
 * and other configuration packets are consumed by the proxy (both backends are required to share
 * the same world template, making the client's existing registries valid for the target server).
 *
 * <p>Configuration content that requires real client interaction (an unapplied resource pack, a
 * cookie request, a code of conduct) cannot be answered by the proxy. In that case the attempt is
 * transparently downgraded to the regular configuration-state switch: the client shows the usual
 * loading screen, but nothing breaks.</p>
 */
public class SeamlessConfigSessionHandler implements MinecraftSessionHandler {

  private static final Logger logger = LogManager.getLogger(SeamlessConfigSessionHandler.class);

  private final VelocityServer server;
  private final VelocityServerConnection serverConn;
  private final CompletableFuture<Impl> resultFuture;
  private final SeamlessSwitchController controller;

  SeamlessConfigSessionHandler(VelocityServer server, VelocityServerConnection serverConn,
      CompletableFuture<Impl> resultFuture, SeamlessSwitchController controller) {
    this.server = server;
    this.serverConn = serverConn;
    this.resultFuture = resultFuture;
    this.controller = controller;
  }

  @Override
  public void activated() {
    // The backend expects the client brand during configuration; replay the stored one.
    final ConnectedPlayer player = serverConn.getPlayer();
    final String brand = player.getClientBrand();
    if (brand != null) {
      final ByteBuf buf = Unpooled.buffer();
      ProtocolUtils.writeString(buf, brand);
      // Seamless transfers require 1.20.2+, where the brand channel is always "minecraft:brand".
      serverConn.ensureConnected().write(new PluginMessagePacket("minecraft:brand", buf));
    }
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
    // Answered by the proxy; the client is still busy playing on the source server.
    serverConn.ensureConnected().write(packet);
    return true;
  }

  @Override
  public boolean handle(KnownPacksPacket packet) {
    // Claim to know no packs, so the server sends its full registry data, which we then drop.
    // Replying with the client's actual known packs would require the server to omit registry
    // entries the client cannot re-derive mid-play.
    serverConn.ensureConnected().write(new KnownPacksPacket(List.of()));
    return true;
  }

  @Override
  public boolean handle(RegistrySyncPacket packet) {
    // Dropped: the client keeps the registries of the source server. Both servers are required
    // to share the same world template for seamless transfers to be enabled.
    return true;
  }

  @Override
  public boolean handle(TagsUpdatePacket packet) {
    return true;
  }

  @Override
  public boolean handle(final ResourcePackRequestPacket packet) {
    // A pack the client already has can be acknowledged truthfully. Anything else would require
    // a real client-side download prompt, which is impossible mid-play -> downgrade.
    final ConnectedPlayer player = serverConn.getPlayer();
    if (packet.getHash() != null && !packet.getHash().isEmpty()
        && player.resourcePackHandler().hasPackAppliedByHash(hexToBytes(packet.getHash()))) {
      final MinecraftConnection smc = serverConn.ensureConnected();
      smc.write(new ResourcePackResponsePacket(
          packet.getId(), packet.getHash(), PlayerResourcePackStatusEvent.Status.ACCEPTED));
      smc.write(new ResourcePackResponsePacket(
          packet.getId(), packet.getHash(), PlayerResourcePackStatusEvent.Status.DOWNLOADED));
      smc.write(new ResourcePackResponsePacket(
          packet.getId(), packet.getHash(), PlayerResourcePackStatusEvent.Status.SUCCESSFUL));
      return true;
    }
    return downgrade("target server requires a resource pack the client has not applied", packet);
  }

  @Override
  public boolean handle(ClientboundCookieRequestPacket packet) {
    return downgrade("target server requested a cookie", packet);
  }

  @Override
  public boolean handle(ClientboundStoreCookiePacket packet) {
    return downgrade("target server stores a cookie", packet);
  }

  @Override
  public boolean handle(CodeOfConductPacket packet) {
    return downgrade("target server requires accepting a code of conduct", packet);
  }

  @Override
  public boolean handle(FinishedUpdatePacket packet) {
    // Acknowledge on behalf of the client, then move the backend connection into PLAY and start
    // buffering the world snapshot.
    final MinecraftConnection smc = serverConn.ensureConnected();
    smc.getChannel().pipeline().get(MinecraftVarintFrameDecoder.class).setState(StateRegistry.PLAY);
    smc.getChannel().pipeline().get(MinecraftDecoder.class).setState(StateRegistry.PLAY);
    smc.write(FinishedUpdatePacket.INSTANCE);
    smc.setActiveSessionHandler(StateRegistry.PLAY,
        new SeamlessTransitionSessionHandler(server, serverConn, controller));
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
    // Configuration-phase plugin messages cannot safely reach a PLAY-state client; drop them.
    if (controller.getConfig().verbose()) {
      logger.info("Seamless transfer for {}: dropping config plugin message on channel {}",
          serverConn.getPlayer().getUsername(), packet.getChannel());
    }
    return true;
  }

  @Override
  public void handleGeneric(MinecraftPacket packet) {
    // Feature flags, report details, server links and other config-only content is dropped; the
    // client keeps the equivalents it received from the source server.
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    // Dropped, same as handleGeneric.
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

  /**
   * Downgrades this attempt to the regular configuration-state switch. The client is moved into
   * the CONFIG state exactly like the vanilla path does; configuration packets that arrive before
   * the client is ready (including the packet that triggered the downgrade) are queued and
   * replayed once the client has acknowledged the state change.
   *
   * @param reason  why the downgrade happened (logged)
   * @param trigger the packet that triggered the downgrade, replayed to the vanilla handler
   * @return always true, for use in {@code handle} methods
   */
  private boolean downgrade(String reason, MinecraftPacket trigger) {
    final ConnectedPlayer player = serverConn.getPlayer();
    logger.info("Seamless transfer for {} -> {} downgraded to a regular switch: {}",
        player.getUsername(), serverConn.getServerInfo().getName(), reason);
    controller.markDowngraded();

    final MinecraftConnection smc = serverConn.ensureConnected();
    final ConfigSessionHandler vanilla = new ConfigSessionHandler(server, serverConn, resultFuture);
    final DowngradeBufferSessionHandler bufferHandler =
        new DowngradeBufferSessionHandler(vanilla, serverConn);
    smc.setAutoReading(false);
    smc.setActiveSessionHandler(StateRegistry.CONFIG, bufferHandler);
    bufferHandler.queue(trigger);

    if (player.getConnection()
        .getActiveSessionHandler() instanceof ClientPlaySessionHandler playHandler) {
      playHandler.doSwitch().thenRunAsync(() -> {
        bufferHandler.replayAndActivate(smc);
        smc.setAutoReading(true);
      }, smc.eventLoop()).exceptionally(exc -> {
        logger.error("Error downgrading seamless transfer for {}", player.getUsername(), exc);
        resultFuture.completeExceptionally(exc);
        return null;
      });
    } else {
      // Should be impossible: seamless transfers only start while the client is in PLAY.
      serverConn.disconnect();
      resultFuture.complete(ConnectionRequestResults.forDisconnect(
          ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR, serverConn.getServer()));
    }
    return true;
  }

  private static byte[] hexToBytes(String hash) {
    try {
      return io.netty.buffer.ByteBufUtil.decodeHexDump(hash);
    } catch (IllegalArgumentException e) {
      return new byte[0];
    }
  }

  /**
   * Buffers configuration packets from the target server while the client is still transitioning
   * into the CONFIG state after a downgrade, then replays them into the vanilla
   * {@link ConfigSessionHandler}.
   */
  private static final class DowngradeBufferSessionHandler implements MinecraftSessionHandler {

    private final ConfigSessionHandler delegate;
    private final VelocityServerConnection serverConn;
    private final ArrayDeque<Object> queued = new ArrayDeque<>();

    DowngradeBufferSessionHandler(ConfigSessionHandler delegate,
        VelocityServerConnection serverConn) {
      this.delegate = delegate;
      this.serverConn = serverConn;
    }

    void queue(Object msg) {
      ReferenceCountUtil.retain(msg);
      queued.add(msg);
    }

    void replayAndActivate(MinecraftConnection smc) {
      smc.setActiveSessionHandler(StateRegistry.CONFIG, delegate);
      Object msg;
      while ((msg = queued.poll()) != null) {
        try {
          if (msg instanceof MinecraftPacket packet) {
            if (!packet.handle(delegate)) {
              delegate.handleGeneric(packet);
            }
          } else if (msg instanceof ByteBuf buf) {
            delegate.handleUnknown(buf);
          }
        } finally {
          ReferenceCountUtil.release(msg);
        }
      }
    }

    @Override
    public boolean beforeHandle() {
      if (!serverConn.isActive()) {
        serverConn.disconnect();
        return true;
      }
      return false;
    }

    @Override
    public boolean handle(DisconnectPacket packet) {
      // No client dependency; let the vanilla handler process it immediately.
      return delegate.handle(packet);
    }

    @Override
    public void handleGeneric(MinecraftPacket packet) {
      queue(packet);
    }

    @Override
    public void handleUnknown(ByteBuf buf) {
      queue(buf);
    }

    @Override
    public void disconnected() {
      release();
      delegate.disconnected();
    }

    @Override
    public void exception(Throwable throwable) {
      release();
      delegate.exception(throwable);
    }

    private void release() {
      Object msg;
      while ((msg = queued.poll()) != null) {
        ReferenceCountUtil.release(msg);
      }
    }
  }
}
