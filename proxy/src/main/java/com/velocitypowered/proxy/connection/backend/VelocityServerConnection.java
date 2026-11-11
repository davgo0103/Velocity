/*
 * Copyright (C) 2018-2023 Velocity Contributors
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

import static com.velocitypowered.proxy.connection.forge.legacy.LegacyForgeConstants.HANDSHAKE_HOSTNAME_TOKEN;
import static com.velocitypowered.proxy.network.Connections.HANDLER;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
import com.velocitypowered.api.network.HandshakeIntent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.PluginMessageEncoder;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.PlayerInfoForwarding;
import com.velocitypowered.proxy.connection.ConnectionTypes;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftConnectionAssociation;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.PlayerDataForwarding;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.forge.modern.ModernForgeConnectionType;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.HandshakePacket;
import com.velocitypowered.proxy.protocol.packet.JoinGamePacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.ServerLoginPacket;
import com.velocitypowered.proxy.protocol.util.ByteBufDataOutput;
import com.velocitypowered.proxy.server.VelocityRegisteredServer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jetbrains.annotations.NotNull;

/**
 * Handles a connection from the proxy to some backend server.
 */
public class VelocityServerConnection implements MinecraftConnectionAssociation, ServerConnection {

  private final VelocityRegisteredServer registeredServer;
  private final @Nullable VelocityRegisteredServer previousServer;
  private final ConnectedPlayer proxyPlayer;
  private final VelocityServer server;
  private @Nullable MinecraftConnection connection;
  private boolean hasCompletedJoin = false;
  private boolean clientLoaded = false; // 1.21.4+
  private boolean gracefulDisconnect = false;
  private BackendConnectionPhase connectionPhase = BackendConnectionPhases.UNKNOWN;
  private final Map<Long, Long> pendingPings = new HashMap<>();
  // Seamless transfers: entity ids this server spawned on the client (client id space), for
  // ghost cleanup when the player is hot-swapped away. Bounded so a misbehaving backend cannot
  // grow it without limit; a player realistically sees a few thousand entities at most.
  private static final int MAX_TRACKED_ENTITY_IDS = 1 << 14;
  private final IntSet trackedEntityIds = new IntOpenHashSet();
  private long suppressLoadScreenUntilNanos;
  private SeamlessPacketIds.@Nullable Table seamlessIds;
  private boolean seamlessIdsResolved;
  private @MonotonicNonNull Integer entityId;

  /**
   * Initializes a new server connection.
   *
   * @param registeredServer the server to connect to
   * @param previousServer   the server the player is coming from
   * @param proxyPlayer      the player connecting to the server
   * @param server           the Velocity proxy instance
   */
  public VelocityServerConnection(VelocityRegisteredServer registeredServer,
      @Nullable VelocityRegisteredServer previousServer,
      ConnectedPlayer proxyPlayer, VelocityServer server) {
    this.registeredServer = registeredServer;
    this.previousServer = previousServer;
    this.proxyPlayer = proxyPlayer;
    this.server = server;
  }

  /**
   * Connects to the server.
   *
   * @return a {@link com.velocitypowered.api.proxy.ConnectionRequestBuilder.Result}
   *     representing whether the connection succeeded
   */
  public CompletableFuture<Impl> connect() {
    CompletableFuture<Impl> result = new CompletableFuture<>();
    // Note: we use the event loop for the connection the player is on. This reduces context
    // switches.
    server.createBootstrap(proxyPlayer.getConnection().eventLoop())
        .handler(server.getBackendChannelInitializer())
        .connect(registeredServer.getServerInfo().getAddress())
        .addListener((ChannelFutureListener) future -> {
          if (future.isSuccess()) {
            connection = new MinecraftConnection(future.channel(), server,
                proxyPlayer.getConnection().getSessionId());
            connection.setAssociation(VelocityServerConnection.this);
            future.channel().pipeline().addLast(HANDLER, connection);

            // Kick off the connection process
            if (!connection.setActiveSessionHandler(StateRegistry.HANDSHAKE)) {
              MinecraftSessionHandler handler =
                  new LoginSessionHandler(server, VelocityServerConnection.this, result);
              connection.setActiveSessionHandler(StateRegistry.HANDSHAKE, handler);
              connection.addSessionHandler(StateRegistry.LOGIN, handler);
            }

            // Set the connection phase, which may, for future forge (or whatever), be
            // determined
            // at this point already
            connectionPhase = connection.getType().getInitialBackendPhase();
            startHandshake();
          } else {
            // Complete the result immediately. ConnectedPlayer will reset the in-flight
            // connection.
            result.completeExceptionally(future.cause());
          }
        });
    return result;
  }

  String getPlayerRemoteAddressAsString() {
    final String addr = proxyPlayer.getRemoteAddress().getAddress().getHostAddress();
    int ipv6ScopeIdx = addr.indexOf('%');
    if (ipv6ScopeIdx == -1) {
      return addr;
    } else {
      return addr.substring(0, ipv6ScopeIdx);
    }
  }

  private String createLegacyForwardingAddress() {
    return PlayerDataForwarding.createLegacyForwardingAddress(
      proxyPlayer.getVirtualHost().orElseGet(() ->
        registeredServer.getServerInfo().getAddress()).getHostString(),
      getPlayerRemoteAddressAsString(),
      proxyPlayer.getGameProfile()
    );
  }

  private String createBungeeGuardForwardingAddress(byte[] forwardingSecret) {
    return PlayerDataForwarding.createBungeeGuardForwardingAddress(
      proxyPlayer.getVirtualHost().orElseGet(() ->
        registeredServer.getServerInfo().getAddress()).getHostString(),
      getPlayerRemoteAddressAsString(),
      proxyPlayer.getGameProfile(),
      forwardingSecret
    );
  }

  private void startHandshake() {
    final MinecraftConnection mc = ensureConnected();
    PlayerInfoForwarding forwardingMode = server.getConfiguration().getPlayerInfoForwardingMode();

    // Initiate the handshake.
    ProtocolVersion protocolVersion = proxyPlayer.getConnection().getProtocolVersion();
    String playerVhost = proxyPlayer.getVirtualHost()
                .orElseGet(() -> registeredServer.getServerInfo().getAddress())
                .getHostString();

    HandshakePacket handshake = new HandshakePacket();
    handshake.setIntent(HandshakeIntent.LOGIN);
    handshake.setProtocolVersion(protocolVersion);
    if (forwardingMode == PlayerInfoForwarding.LEGACY) {
      handshake.setServerAddress(createLegacyForwardingAddress());
    } else if (forwardingMode == PlayerInfoForwarding.BUNGEEGUARD) {
      byte[] secret = server.getConfiguration().getForwardingSecret();
      handshake.setServerAddress(createBungeeGuardForwardingAddress(secret));
    } else if (proxyPlayer.getConnection().getType() == ConnectionTypes.LEGACY_FORGE) {
      handshake.setServerAddress(playerVhost + HANDSHAKE_HOSTNAME_TOKEN);
    } else if (proxyPlayer.getConnection().getType() instanceof ModernForgeConnectionType forgeConnection) {
      handshake.setServerAddress(playerVhost + forgeConnection.getModernToken());
    } else {
      handshake.setServerAddress(playerVhost);
    }

    handshake.setPort(proxyPlayer.getVirtualHost()
            .orElseGet(() -> registeredServer.getServerInfo().getAddress())
            .getPort());
    mc.delayedWrite(handshake);

    mc.setProtocolVersion(protocolVersion);
    mc.setActiveSessionHandler(StateRegistry.LOGIN);
    if (proxyPlayer.getIdentifiedKey() == null
        && proxyPlayer.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_19_3)) {
      mc.delayedWrite(new ServerLoginPacket(proxyPlayer.getUsername(), proxyPlayer.getUniqueId()));
    } else {
      mc.delayedWrite(new ServerLoginPacket(proxyPlayer.getUsername(),
              proxyPlayer.getIdentifiedKey()));
    }
    mc.flush();
  }

  public @Nullable MinecraftConnection getConnection() {
    return connection;
  }

  /**
   * Ensures the connection is still active and throws an exception if it is not.
   *
   * @return the active connection
   * @throws IllegalStateException if the connection is inactive
   */
  public MinecraftConnection ensureConnected() {
    if (connection == null) {
      throw new IllegalStateException("Not connected to server!");
    }
    return connection;
  }

  @Override
  public VelocityRegisteredServer getServer() {
    return registeredServer;
  }

  @Override
  public Optional<RegisteredServer> getPreviousServer() {
    return Optional.ofNullable(this.previousServer);
  }

  @Override
  public ServerInfo getServerInfo() {
    return registeredServer.getServerInfo();
  }

  @Override
  public ConnectedPlayer getPlayer() {
    return proxyPlayer;
  }

  /**
   * Disconnects from the server.
   */
  public void disconnect() {
    if (connection != null) {
      gracefulDisconnect = true;
      connection.close(false);
      connection = null;
    }
  }

  @Override
  public String toString() {
    return "[server connection] " + proxyPlayer.getGameProfile().getName() + " -> "
        + registeredServer.getServerInfo().getName();
  }

  @Override
  public boolean sendPluginMessage(
          final @NotNull ChannelIdentifier identifier,
          final byte @NotNull [] data
  ) {
    return sendPluginMessage(identifier, Unpooled.wrappedBuffer(data));
  }

  @Override
  public boolean sendPluginMessage(
          final @NotNull ChannelIdentifier identifier,
          final @NotNull PluginMessageEncoder dataEncoder
  ) {
    requireNonNull(identifier);
    requireNonNull(dataEncoder);
    final ByteBuf buf = Unpooled.buffer();
    final ByteBufDataOutput dataOutput = new ByteBufDataOutput(buf);
    dataEncoder.encode(dataOutput);
    if (buf.isReadable()) {
      return sendPluginMessage(identifier, buf);
    } else {
      buf.release();
      return false;
    }
  }

  /**
   * Sends a plugin message to the server through this connection.
   *
   * @param identifier the channel ID to use
   * @param data       the data
   * @return whether or not the message was sent
   */
  public boolean sendPluginMessage(ChannelIdentifier identifier, ByteBuf data) {
    Preconditions.checkNotNull(identifier, "identifier");
    Preconditions.checkNotNull(data, "data");

    final MinecraftConnection mc = ensureConnected();

    final PluginMessagePacket message = new PluginMessagePacket(identifier.getId(), data);
    mc.write(message);
    return true;
  }

  /**
   * Indicates that we have completed the plugin process.
   */
  public void completeJoin() {
    if (!hasCompletedJoin) {
      hasCompletedJoin = true;
      if (connectionPhase == BackendConnectionPhases.UNKNOWN) {
        // Now we know
        connectionPhase = BackendConnectionPhases.VANILLA;
        if (connection != null) {
          connection.setType(ConnectionTypes.VANILLA);
        }
      }
    }
  }

  public void setClientLoaded(boolean clientLoaded) {
    this.clientLoaded = clientLoaded;
  }

  @Override
  public boolean isClientLoaded() {
    return clientLoaded;
  }

  boolean isGracefulDisconnect() {
    return gracefulDisconnect;
  }

  public Map<Long, Long> getPendingPings() {
    return pendingPings;
  }

  /**
   * Entity ids this server has spawned on the client (in the client's id space), tracked for
   * seamless-transfer ghost cleanup. Only populated for servers in a seamless group; may
   * over-approximate (ids the client already unloaded), which is harmless for the Remove
   * Entities cleanup it feeds.
   *
   * @return the mutable set of tracked entity ids
   */
  public IntSet getTrackedEntityIds() {
    return trackedEntityIds;
  }

  /**
   * Returns the seamless-transfer packet ids for this connection, or null when this server can
   * never take part in a seamless transfer (feature disabled, server in no group, or protocol
   * version unsupported) — in which case no per-packet seamless work is done at all.
   *
   * @return the packet id table, or null
   */
  public SeamlessPacketIds.@Nullable Table getSeamlessIds() {
    if (!seamlessIdsResolved) {
      final var config = server.getConfiguration().getSeamlessTransfersConfig();
      seamlessIds = config.enabled() && config.isInAnyGroup(getServerInfo().getName())
          ? SeamlessPacketIds.forVersion(proxyPlayer.getProtocolVersion()) : null;
      seamlessIdsResolved = true;
    }
    return seamlessIds;
  }

  /**
   * Seamless-transfer processing of a raw packet this server sends to the client: translates
   * the player's entity id (after a hot-swap), tracks spawned entities for ghost cleanup and
   * counts signed chat messages for the acknowledgement bridge.
   *
   * @param buf the raw packet; ownership is taken over
   * @return the packet to write to the client, owned by the caller
   */
  public ByteBuf processSeamlessClientbound(ByteBuf buf) {
    final SeamlessPacketIds.Table ids = getSeamlessIds();
    if (ids == null) {
      return buf;
    }
    ByteBuf out = buf;
    final SeamlessEntityIdRemapper remapper = proxyPlayer.getEntityIdRemapper();
    if (remapper != null) {
      out = remapper.rewriteClientbound(buf);
      if (out != buf) {
        buf.release();
      }
    }
    trackEntityIds(out, ids);
    if (SeamlessPacketIds.peekVarInt(out) == ids.playerChat()) {
      if (SeamlessPacketIds.isSignedPlayerChat(out, ids, proxyPlayer.getProtocolVersion())) {
        proxyPlayer.getChatQueue().onBackendSignedMessage();
      }
      if (proxyPlayer.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_21_5)) {
        out = rewriteChatGlobalIndex(out);
      }
    }
    return out;
  }

  /** Shifts a 1.21.5+ player chat packet's global index into the client's sequence. */
  private ByteBuf rewriteChatGlobalIndex(ByteBuf buf) {
    try {
      final ByteBuf in = buf.duplicate();
      final int packetId = ProtocolUtils.readVarInt(in);
      final int backendIndex = ProtocolUtils.readVarInt(in);
      final int clientIndex = proxyPlayer.toClientChatIndex(backendIndex);
      if (clientIndex == backendIndex) {
        return buf;
      }
      final ByteBuf out = buf.alloc().buffer(buf.readableBytes() + 5);
      ProtocolUtils.writeVarInt(out, packetId);
      ProtocolUtils.writeVarInt(out, clientIndex);
      out.writeBytes(in);
      buf.release();
      return out;
    } catch (RuntimeException e) {
      return buf; // malformed: forward untouched
    }
  }

  private void trackEntityIds(ByteBuf buf, SeamlessPacketIds.Table ids) {
    final int packetId = SeamlessPacketIds.peekVarInt(buf);
    final boolean spawn = ids.isSpawn(packetId);
    if (!spawn && packetId != ids.removeEntities()) {
      return;
    }
    try {
      final ByteBuf in = buf.duplicate();
      ProtocolUtils.readVarInt(in); // packet id
      if (spawn) {
        if (trackedEntityIds.size() < MAX_TRACKED_ENTITY_IDS) {
          trackedEntityIds.add(ProtocolUtils.readVarInt(in));
        }
      } else {
        final int count = Math.min(ProtocolUtils.readVarInt(in), MAX_TRACKED_ENTITY_IDS);
        for (int i = 0; i < count && in.isReadable(); i++) {
          trackedEntityIds.remove(ProtocolUtils.readVarInt(in));
        }
      }
    } catch (RuntimeException e) {
      // Malformed or truncated varints: ignore, tracking is best-effort.
    }
  }

  /**
   * While a seamless transfer's join sequence is running, the backend's "start waiting for
   * chunks" game events are dropped so the client — which already has the shared world — never
   * opens the "loading terrain" screen (on ≤1.21.1 the event opens it unconditionally, causing
   * an intermittent one-frame flash). The window is cancelled early if the backend sends a
   * Respawn (a genuine world change, where the event is needed).
   *
   * @return the {@link System#nanoTime()} deadline until which the event is suppressed
   */
  public long getSuppressLoadScreenUntilNanos() {
    return suppressLoadScreenUntilNanos;
  }

  public void setSuppressLoadScreenUntilNanos(long deadlineNanos) {
    this.suppressLoadScreenUntilNanos = deadlineNanos;
  }

  public Integer getEntityId() {
    return entityId;
  }

  public void setEntityId(Integer entityId) {
    this.entityId = entityId;
  }

  /**
   * Ensures that this server connection remains "active": the connection is established and not
   * closed, the player is still connected to the server, and the player still remains online.
   *
   * @return whether or not the player is online
   */
  public boolean isActive() {
    return connection != null && !connection.isClosed() && !gracefulDisconnect
        && proxyPlayer.isActive();
  }

  /**
   * Gets the current "phase" of the connection, mostly used for tracking modded negotiation for
   * legacy forge servers and provides methods for performing phase specific actions.
   *
   * @return The {@link BackendConnectionPhase}
   */
  public BackendConnectionPhase getPhase() {
    return connectionPhase;
  }

  /**
   * Sets the current "phase" of the connection. See {@link #getPhase()}
   *
   * @param connectionPhase The {@link BackendConnectionPhase}
   */
  public void setConnectionPhase(BackendConnectionPhase connectionPhase) {
    this.connectionPhase = connectionPhase;
  }

  /**
   * Gets whether the {@link JoinGamePacket} packet has been
   * sent by this server.
   *
   * @return Whether the join has been completed.
   */
  public boolean hasCompletedJoin() {
    return hasCompletedJoin;
  }
}
