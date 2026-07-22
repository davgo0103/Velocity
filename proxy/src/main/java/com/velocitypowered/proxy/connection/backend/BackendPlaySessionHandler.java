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

import static com.velocitypowered.proxy.connection.backend.BungeeCordMessageResponder.getBungeeCordChannel;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PreTransferEvent;
import com.velocitypowered.api.event.player.CookieRequestEvent;
import com.velocitypowered.api.event.player.CookieStoreEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerResourcePackRemoveEvent;
import com.velocitypowered.api.event.player.ServerResourcePackSendEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.command.CommandGraphInjector;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.client.ClientPlaySessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.player.resourcepack.VelocityResourcePackInfo;
import com.velocitypowered.proxy.connection.player.resourcepack.handler.ResourcePackHandler;
import com.velocitypowered.proxy.connection.util.ConnectionMessages;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import com.velocitypowered.proxy.protocol.packet.AvailableCommandsPacket;
import com.velocitypowered.proxy.protocol.packet.BossBarPacket;
import com.velocitypowered.proxy.protocol.packet.BundleDelimiterPacket;
import com.velocitypowered.proxy.protocol.packet.ClientSettingsPacket;
import com.velocitypowered.proxy.protocol.packet.ClientboundCookieRequestPacket;
import com.velocitypowered.proxy.protocol.packet.ClientboundStoreCookiePacket;
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;
import com.velocitypowered.proxy.protocol.packet.LegacyPlayerListItemPacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.RemovePlayerInfoPacket;
import com.velocitypowered.proxy.protocol.packet.RemoveResourcePackPacket;
import com.velocitypowered.proxy.protocol.packet.ResourcePackRequestPacket;
import com.velocitypowered.proxy.protocol.packet.ResourcePackResponsePacket;
import com.velocitypowered.proxy.protocol.packet.ServerDataPacket;
import com.velocitypowered.proxy.protocol.packet.TabCompleteResponsePacket;
import com.velocitypowered.proxy.protocol.packet.TransferPacket;
import com.velocitypowered.proxy.protocol.packet.UpsertPlayerInfoPacket;
import com.velocitypowered.proxy.protocol.packet.chat.ComponentHolder;
import com.velocitypowered.proxy.protocol.packet.config.StartUpdatePacket;
import com.velocitypowered.proxy.protocol.util.DeferredByteBufHolder;
import com.velocitypowered.proxy.protocol.util.PluginMessageUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.InetSocketAddress;
import java.util.regex.Pattern;
import net.kyori.adventure.key.Key;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handles a connected player.
 */
public class BackendPlaySessionHandler implements MinecraftSessionHandler {

  private static final Pattern PLAUSIBLE_SHA1_HASH = Pattern.compile("^[a-z0-9]{40}$");
  private static final Logger logger = LogManager.getLogger(BackendPlaySessionHandler.class);
  private static final boolean BACKPRESSURE_LOG =
      Boolean.getBoolean("velocity.log-server-backpressure");
  private static final int MAXIMUM_PACKETS_TO_FLUSH =
      Integer.getInteger("velocity.max-packets-per-flush", 8192);
  private static final int LARGE_PACKET_THRESHOLD = 1024 * 128;

  // Safety cap so a misbehaving backend cannot grow the set without bound. A player realistically
  // sees at most a few thousand entities within render distance, so 16384 is a generous ceiling
  // that keeps the (boxed) set's worst-case memory small.
  private static final int MAX_TRACKED_ENTITY_IDS = 1 << 14;

  private final VelocityServer server;
  private final VelocityServerConnection serverConn;
  private final ClientPlaySessionHandler playerSessionHandler;
  private final MinecraftConnection playerConnection;
  private final BungeeCordMessageResponder bungeecordMessageResponder;
  private final boolean trackEntityIds;
  private final int addEntityPacketId;
  private final int removeEntitiesPacketId;
  private boolean exceptionTriggered = false;
  private int packetsFlushed;

  BackendPlaySessionHandler(VelocityServer server, VelocityServerConnection serverConn) {
    this.server = server;
    this.serverConn = serverConn;
    this.playerConnection = serverConn.getPlayer().getConnection();

    // Entity packet ids are selected automatically from the connection's protocol version, so
    // ghost cleanup needs no per-server configuration; it is simply skipped on versions whose
    // ids are not yet in the table.
    final ProtocolVersion version = serverConn.getPlayer().getProtocolVersion();
    this.addEntityPacketId = SeamlessPacketIds.addEntityId(version);
    this.removeEntitiesPacketId = SeamlessPacketIds.removeEntitiesId(version);
    this.trackEntityIds = server.getConfiguration().getSeamlessTransfersConfig().enabled()
        && SeamlessPacketIds.entityCleanupSupported(version);

    MinecraftSessionHandler psh = playerConnection.getActiveSessionHandler();
    if (!(psh instanceof ClientPlaySessionHandler)) {
      throw new IllegalStateException(
          "Initializing BackendPlaySessionHandler with no backing client play session handler!");
    }
    this.playerSessionHandler = (ClientPlaySessionHandler) psh;

    this.bungeecordMessageResponder = new BungeeCordMessageResponder(server,
        serverConn.getPlayer());
  }

  @Override
  public void activated() {
    serverConn.getServer().addPlayer(serverConn.getPlayer());

    MinecraftConnection serverMc = serverConn.ensureConnected();
    if (server.getConfiguration().isBungeePluginChannelEnabled()) {
      serverMc.write(PluginMessageUtil.constructChannelsPacket(serverMc.getProtocolVersion(),
          ImmutableList.of(getBungeeCordChannel(serverMc.getProtocolVersion()))
      ));
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
  public boolean handle(BundleDelimiterPacket bundleDelimiterPacket) {
    serverConn.getPlayer().getBundleHandler().toggleBundleSession();
    return false;
  }

  @Override
  public boolean handle(StartUpdatePacket packet) {
    // If the current server re-enters configuration while a seamless transfer is being prepared,
    // the transfer cannot continue: the client is about to be reset. Abort it; the connection
    // request completes unsuccessfully and the player simply follows the reconfiguration.
    final SeamlessSwitchController seamlessController =
        serverConn.getPlayer().getSeamlessSwitchController();
    if (seamlessController != null) {
      seamlessController.abort(null, null);
    }
    // The configuration state resets the client's entities; the tracked ids are obsolete.
    serverConn.getTrackedEntityIds().clear();
    MinecraftConnection smc = serverConn.ensureConnected();
    smc.setAutoReading(false);
    // Even when not auto reading messages are still decoded. Decode them with the correct state
    smc.getChannel().pipeline().get(MinecraftVarintFrameDecoder.class).setState(StateRegistry.CONFIG);
    smc.getChannel().pipeline().get(MinecraftDecoder.class).setState(StateRegistry.CONFIG);
    serverConn.getPlayer().switchToConfigState();
    return true;
  }

  @Override
  public boolean handle(KeepAlivePacket packet) {
    serverConn.getPendingPings().put(packet.getRandomId(), System.nanoTime());
    return false; // forwards on
  }


  @Override
  public boolean handle(ClientSettingsPacket packet) {
    serverConn.ensureConnected().write(packet);
    return true;
  }

  @Override
  public boolean handle(DisconnectPacket packet) {
    serverConn.disconnect();
    serverConn.getPlayer().handleConnectionException(serverConn.getServer(), packet, true);
    return true;
  }

  @Override
  public boolean handle(BossBarPacket packet) {
    // Tracked for all versions: pre-1.20.2 clients need explicit removal on a regular switch,
    // and seamless (PLAY-state) switches need it on 1.20.2+ as well.
    if (packet.getAction() == BossBarPacket.ADD) {
      playerSessionHandler.getServerBossBars().add(packet.getUuid());
    } else if (packet.getAction() == BossBarPacket.REMOVE) {
      playerSessionHandler.getServerBossBars().remove(packet.getUuid());
    }
    return false; // forward
  }

  @Override
  public boolean handle(final ResourcePackRequestPacket packet) {
    final ResourcePackInfo.Builder builder = new VelocityResourcePackInfo.BuilderImpl(
        Preconditions.checkNotNull(packet.getUrl()))
        .setId(packet.getId())
        .setPrompt(packet.getPrompt() == null ? null : packet.getPrompt().getComponent())
        .setShouldForce(packet.isRequired())
        .setOrigin(ResourcePackInfo.Origin.DOWNSTREAM_SERVER);

    final String hash = packet.getHash();
    if (hash != null && !hash.isEmpty()) {
      if (PLAUSIBLE_SHA1_HASH.matcher(hash).matches()) {
        builder.setHash(ByteBufUtil.decodeHexDump(hash));
      }
    }

    final ResourcePackInfo resourcePackInfo = builder.build();
    final ServerResourcePackSendEvent event = new ServerResourcePackSendEvent(
            resourcePackInfo, this.serverConn);
    server.getEventManager().fire(event).thenAcceptAsync(serverResourcePackSendEvent -> {
      if (playerConnection.isClosed()) {
        return;
      }
      if (serverResourcePackSendEvent.getResult().isAllowed()) {
        final ResourcePackInfo toSend = serverResourcePackSendEvent.getProvidedResourcePack();
        boolean modifiedPack = false;
        if (toSend != serverResourcePackSendEvent.getReceivedResourcePack()) {
          ((VelocityResourcePackInfo) toSend)
              .setOriginalOrigin(ResourcePackInfo.Origin.DOWNSTREAM_SERVER);
          modifiedPack = true;
        }
        if (serverConn.getPlayer().resourcePackHandler().hasPackAppliedByHash(toSend.getHash())) {
          // Do not apply a resource pack that has already been applied
          if (serverConn.getConnection() != null) {
            serverConn.getConnection().write(new ResourcePackResponsePacket(
                    packet.getId(), packet.getHash(), PlayerResourcePackStatusEvent.Status.ACCEPTED));
            if (serverConn.getConnection().getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_20_3)) {
              serverConn.getConnection().write(new ResourcePackResponsePacket(
                  packet.getId(), packet.getHash(),
                  PlayerResourcePackStatusEvent.Status.DOWNLOADED));
            }
            serverConn.getConnection().write(new ResourcePackResponsePacket(
                packet.getId(), packet.getHash(),
                PlayerResourcePackStatusEvent.Status.SUCCESSFUL));
          }
          if (modifiedPack) {
            logger.warn("A plugin has tried to modify a ResourcePack provided by the backend server "
                    + "with a ResourcePack already applied, the applying of the resource pack will be skipped.");
          }
        } else {
          serverConn.getPlayer().resourcePackHandler().queueResourcePack(toSend);
        }
      } else if (serverConn.getConnection() != null) {
        serverConn.getConnection().write(new ResourcePackResponsePacket(
            packet.getId(),
            packet.getHash(),
            PlayerResourcePackStatusEvent.Status.DECLINED
        ));
      }
    }, playerConnection.eventLoop()).exceptionally((ex) -> {
      if (serverConn.getConnection() != null) {
        serverConn.getConnection().write(new ResourcePackResponsePacket(
            packet.getId(),
            packet.getHash(),
            PlayerResourcePackStatusEvent.Status.DECLINED
        ));
      }
      logger.error("Exception while handling resource pack send for {}", playerConnection, ex);
      return null;
    });

    return true;
  }

  @Override
  public boolean handle(RemoveResourcePackPacket packet) {
    final ServerResourcePackRemoveEvent event = new ServerResourcePackRemoveEvent(
            packet.getId(), this.serverConn);
    server.getEventManager().fire(event).thenAcceptAsync(serverResourcePackRemoveEvent -> {
      if (playerConnection.isClosed()) {
        return;
      }
      if (serverResourcePackRemoveEvent.getResult().isAllowed()) {
        final ConnectedPlayer player = serverConn.getPlayer();
        final ResourcePackHandler handler = player.resourcePackHandler();
        if (packet.getId() != null) {
          handler.remove(packet.getId());
        } else {
          handler.clearAppliedResourcePacks();
        }
        playerConnection.write(packet);
      }
    }, playerConnection.eventLoop()).exceptionally((ex) -> {
      logger.error("Exception while handling resource pack remove for {}", playerConnection, ex);
      return null;
    });
    return true;
  }

  @Override
  public boolean handle(PluginMessagePacket packet) {
    if (bungeecordMessageResponder.process(packet)) {
      return true;
    }

    // Register and unregister packets are simply forwarded to the client as-is.
    if (PluginMessageUtil.isRegister(packet) || PluginMessageUtil.isUnregister(packet)) {
      return false;
    }

    if (PluginMessageUtil.isMcBrand(packet)) {
      PluginMessagePacket rewritten = PluginMessageUtil
              .rewriteMinecraftBrand(packet,
                      server.getVersion(), playerConnection.getProtocolVersion());
      playerConnection.write(rewritten);
      return true;
    }

    if (serverConn.getPhase().handle(serverConn, serverConn.getPlayer(), packet)) {
      // Handled.
      return true;
    }

    ChannelIdentifier id = server.getChannelRegistrar().getFromId(packet.getChannel());
    if (id == null) {
      return false;
    }

    byte[] copy = ByteBufUtil.getBytes(packet.content());
    PluginMessageEvent event = new PluginMessageEvent(serverConn, serverConn.getPlayer(), id, copy);
    server.getEventManager().fire(event).thenAcceptAsync(pme -> {
      if (pme.getResult().isAllowed() && !playerConnection.isClosed()) {
        PluginMessagePacket copied = new PluginMessagePacket(
                packet.getChannel(), Unpooled.wrappedBuffer(copy));
        playerConnection.write(copied);
      }
    }, playerConnection.eventLoop()).exceptionally((ex) -> {
      logger.error("Exception while handling plugin message {}", packet, ex);
      return null;
    });
    return true;
  }

  @Override
  public boolean handle(TabCompleteResponsePacket packet) {
    playerSessionHandler.handleTabCompleteResponse(packet);
    return true;
  }

  @Override
  public boolean handle(LegacyPlayerListItemPacket packet) {
    serverConn.getPlayer().getTabList().processLegacy(packet);
    return false;
  }

  @Override
  public boolean handle(UpsertPlayerInfoPacket packet) {
    serverConn.getPlayer().getTabList().processUpdate(packet);
    return false;
  }

  @Override
  public boolean handle(RemovePlayerInfoPacket packet) {
    serverConn.getPlayer().getTabList().processRemove(packet);
    return false;
  }

  @Override
  public boolean handle(AvailableCommandsPacket commands) {
    RootCommandNode<CommandSource> rootNode = commands.getRootNode();
    if (server.getConfiguration().isAnnounceProxyCommands()) {
      // Inject commands from the proxy.
      final CommandGraphInjector<CommandSource> injector = server.getCommandManager().getInjector();
      injector.inject(rootNode, serverConn.getPlayer());

      // In 1.21.6 a confirmation prompt was added when executing a command via `run_command` click
      // action if the command is unknown. To prevent this prompt we have to send the command.
      if (this.playerConnection.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_21_6)) {
        rootNode.removeChildByName("velocity:callback");
      }
    }

    server.getEventManager().fire(
            new PlayerAvailableCommandsEvent(serverConn.getPlayer(), rootNode))
        .thenAcceptAsync(event -> playerConnection.write(commands), playerConnection.eventLoop())
        .exceptionally((ex) -> {
          logger.error("Exception while handling available commands for {}", playerConnection, ex);
          return null;
        });
    return true;
  }

  @Override
  public boolean handle(ServerDataPacket packet) {
    server.getServerListPingHandler().getInitialPing(this.serverConn.getPlayer()).thenComposeAsync(
        ping -> server.getEventManager()
            .fire(new ProxyPingEvent(this.serverConn.getPlayer(), ping)),
        playerConnection.eventLoop()).thenAcceptAsync(pingEvent -> this.playerConnection.write(
            new ServerDataPacket(new ComponentHolder(
                this.serverConn.ensureConnected().getProtocolVersion(),
                pingEvent.getPing().getDescriptionComponent()),
                pingEvent.getPing().getFavicon().orElse(null), packet.isSecureChatEnforced())),
        playerConnection.eventLoop());
    return true;
  }

  @Override
  public boolean handle(TransferPacket packet) {
    final InetSocketAddress originalAddress = packet.address();
    if (originalAddress == null) {
      logger.error("""
          Unexpected nullable address received in TransferPacket \
          from Backend Server in Play State""");
      return true;
    }
    this.server.getEventManager()
        .fire(new PreTransferEvent(this.serverConn.getPlayer(), originalAddress))
        .thenAcceptAsync(event -> {
          if (event.getResult().isAllowed()) {
            InetSocketAddress resultedAddress = event.getResult().address();
            if (resultedAddress == null) {
              resultedAddress = originalAddress;
            }
            this.playerConnection.write(new TransferPacket(
                    resultedAddress.getHostName(), resultedAddress.getPort()));
          }
        }, playerConnection.eventLoop());
    return true;
  }

  @Override
  public boolean handle(ClientboundStoreCookiePacket packet) {
    server.getEventManager()
        .fire(new CookieStoreEvent(serverConn.getPlayer(), packet.getKey(), packet.getPayload()))
        .thenAcceptAsync(event -> {
          if (event.getResult().isAllowed()) {
            final Key resultedKey = event.getResult().getKey() == null
                ? event.getOriginalKey() : event.getResult().getKey();
            final byte[] resultedData = event.getResult().getData() == null
                ? event.getOriginalData() : event.getResult().getData();

            playerConnection.write(new ClientboundStoreCookiePacket(resultedKey, resultedData));
          }
        }, playerConnection.eventLoop());

    return true;
  }

  @Override
  public boolean handle(ClientboundCookieRequestPacket packet) {
    server.getEventManager().fire(new CookieRequestEvent(serverConn.getPlayer(), packet.getKey()))
        .thenAcceptAsync(event -> {
          if (event.getResult().isAllowed()) {
            final Key resultedKey = event.getResult().getKey() == null
                ? event.getOriginalKey() : event.getResult().getKey();

            playerConnection.write(new ClientboundCookieRequestPacket(resultedKey));
          }
        }, playerConnection.eventLoop());

    return true;
  }

  @Override
  public void handleGeneric(MinecraftPacket packet) {
    if (packet instanceof PluginMessagePacket pluginMessage) {
      pluginMessage.retain();
    }
    boolean huge = packet instanceof DeferredByteBufHolder def && def.content().readableBytes() > LARGE_PACKET_THRESHOLD;
    playerConnection.delayedWrite(packet);
    if (huge || ++packetsFlushed >= MAXIMUM_PACKETS_TO_FLUSH) {
      playerConnection.flush();
      packetsFlushed = 0;
    }
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    if (System.nanoTime() < serverConn.getSuppressLoadScreenUntilNanos()) {
      // Velocity registers Respawn encode-only, so a backend Respawn arrives opaque: a genuine
      // world change during the seamless-join window. The client rebuilds its level and needs
      // the "start waiting for chunks" events again — cancel the suppression before forwarding.
      final int respawnId =
          SeamlessPacketIds.respawnId(serverConn.getPlayer().getProtocolVersion());
      if (respawnId >= 0 && SeamlessPacketIds.peekVarInt(buf) == respawnId) {
        serverConn.setSuppressLoadScreenUntilNanos(0);
        if (server.getConfiguration().getSeamlessTransfersConfig().verbose()) {
          logger.info("Seamless: live Respawn from {} during the join window for {} — genuine "
                  + "world change, load-screen suppression cancelled (a real reload follows)",
              serverConn.getServerInfo().getName(), serverConn.getPlayer().getUsername());
        }
      } else if (SeamlessPacketIds.isLevelLoadStartEvent(buf)) {
        // Straggler "start waiting for chunks" event from the seamless join sequence: the
        // client already has the shared world, and on ≤1.21.1 this event would open the
        // "loading terrain" screen unconditionally. See SeamlessSwitchController.
        if (server.getConfiguration().getSeamlessTransfersConfig().verbose()) {
          logger.info("Seamless: dropped straggler load-screen event from {} for {}",
              serverConn.getServerInfo().getName(), serverConn.getPlayer().getUsername());
        }
        return;
      }
    }
    if (trackEntityIds) {
      trackEntityIds(buf);
    }
    boolean huge = buf.readableBytes() > LARGE_PACKET_THRESHOLD;
    playerConnection.delayedWrite(buf.retain());
    if (huge || ++packetsFlushed >= MAXIMUM_PACKETS_TO_FLUSH) {
      playerConnection.flush();
      packetsFlushed = 0;
    }
  }

  /**
   * Tracks the entity ids this server spawns on the client, so a seamless transfer can remove
   * the leftovers ("ghosts") when the client's level is reused across the switch. Parses only
   * the leading varints of Spawn Entity / Remove Entities packets; ids are configured in
   * [seamless-transfers] since they vary by protocol version.
   *
   * <p>Runs on the hot backend→client passthrough path, so the packet id is peeked without
   * allocating and the vast majority of packets (which are neither Spawn Entity nor Remove
   * Entities) return before touching the ByteBuf reader index.</p>
   */
  private void trackEntityIds(ByteBuf buf) {
    final int packetId = peekVarInt(buf, buf.readerIndex());
    if (packetId != addEntityPacketId && packetId != removeEntitiesPacketId) {
      return;
    }
    try {
      final ByteBuf peek = buf.duplicate();
      ProtocolUtils.readVarInt(peek); // skip the packet id
      final var tracked = serverConn.getTrackedEntityIds();
      if (packetId == addEntityPacketId) {
        if (tracked.size() < MAX_TRACKED_ENTITY_IDS) {
          tracked.add(ProtocolUtils.readVarInt(peek));
        }
      } else {
        final int count = Math.min(ProtocolUtils.readVarInt(peek), MAX_TRACKED_ENTITY_IDS);
        for (int i = 0; i < count && peek.isReadable(); i++) {
          tracked.remove(ProtocolUtils.readVarInt(peek));
        }
      }
    } catch (Exception e) {
      // Malformed or truncated varints: ignore, tracking is best-effort.
    }
  }

  /**
   * Reads a varint at the given absolute index without moving the reader index or allocating.
   *
   * @param buf   the buffer to read from
   * @param index the absolute index of the varint's first byte
   * @return the decoded value, or -1 if it is truncated or longer than 3 bytes
   */
  private static int peekVarInt(ByteBuf buf, int index) {
    int result = 0;
    for (int i = 0; i < 3; i++) {
      if (buf.writerIndex() <= index + i) {
        return -1;
      }
      final byte read = buf.getByte(index + i);
      result |= (read & 0x7F) << (7 * i);
      if ((read & 0x80) == 0) {
        return result;
      }
    }
    return -1;
  }

  @Override
  public void readCompleted() {
    playerConnection.flush();
    packetsFlushed = 0;
  }

  @Override
  public void exception(Throwable throwable) {
    exceptionTriggered = true;
    serverConn.getPlayer().handleConnectionException(serverConn.getServer(), throwable,
        !(throwable instanceof ReadTimeoutException));
  }

  public VelocityServer getServer() {
    return server;
  }

  @Override
  public void disconnected() {
    serverConn.getServer().removePlayer(serverConn.getPlayer());
    if (!serverConn.isGracefulDisconnect() && !exceptionTriggered) {
      if (server.getConfiguration().isFailoverOnUnexpectedServerDisconnect()) {
        serverConn.getPlayer().handleConnectionException(serverConn.getServer(),
            DisconnectPacket.create(ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR,
                serverConn.getPlayer().getProtocolVersion(),
                    serverConn.getPlayer().getConnection().getState()), true);
      } else {
        serverConn.getPlayer().disconnect(ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR);
      }
    }
  }

  @Override
  public void writabilityChanged() {
    Channel serverChan = serverConn.ensureConnected().getChannel();
    boolean writable = serverChan.isWritable();

    if (BACKPRESSURE_LOG) {
      if (writable) {
        logger.info("{} is not writable, not auto-reading player connection data", this.serverConn);
      } else {
        logger.info("{} is writable, will auto-read player connection data", this.serverConn);
      }
    }

    playerConnection.setAutoReading(writable);
  }
}
