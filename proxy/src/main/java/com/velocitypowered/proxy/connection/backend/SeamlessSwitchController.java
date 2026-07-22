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

import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration.SeamlessTransfersConfig;
import com.velocitypowered.proxy.connection.ConnectionTypes;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.client.ClientPlaySessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.util.ConnectionMessages;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.JoinGamePacket;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Orchestrates a seamless (PLAY-state) transfer between two backend servers that share the same
 * world template and player data. Instead of the regular {@code CONFIG}-state switch, which resets
 * the client and shows a loading screen, the target server's configuration phase is answered by
 * the proxy itself and its initial world snapshot is buffered. Once the snapshot looks complete,
 * it is applied to the client with a same-dimension respawn in a single flush, so the "downloading
 * terrain" screen has (nearly) no time to render.
 *
 * <p>State machine: {@code PREPARING} (target logging in / configuring) → {@code BUFFERING}
 * (target in PLAY, snapshot packets accumulating) → {@code COMMITTING} → {@code LIVE}. Any error
 * before {@code COMMITTING} aborts the attempt and leaves the player untouched on the source
 * server.</p>
 *
 * <p>All methods must be called on the player's event loop; the client connection and every
 * backend connection of a player share the same loop.</p>
 */
public final class SeamlessSwitchController {

  private static final Logger logger = LogManager.getLogger(SeamlessSwitchController.class);

  /**
   * The state of a seamless switch attempt.
   */
  public enum State {
    /** The target server is still logging in or being configured by the proxy. */
    PREPARING,
    /** The target server is in PLAY; its snapshot packets are being buffered. */
    BUFFERING,
    /** The snapshot is being applied to the client. */
    COMMITTING,
    /** The switch completed; the controller is defunct. */
    LIVE,
    /** The attempt was aborted or downgraded; the controller is defunct. */
    ABORTED
  }

  private final VelocityServer server;
  private final ConnectedPlayer player;
  private final VelocityServerConnection source;
  private final VelocityServerConnection target;
  private final CompletableFuture<Impl> resultFuture;
  private final SeamlessTransfersConfig config;

  private State state = State.PREPARING;
  private final ArrayDeque<Object> buffer = new ArrayDeque<>();
  private @Nullable JoinGamePacket targetJoinGame;
  private @Nullable ScheduledFuture<?> prepareTimeoutTask;
  private @Nullable ScheduledFuture<?> commitTimeoutTask;
  private @Nullable ScheduledFuture<?> graceCheckTask;

  // Instrumentation, logged on commit.
  private final long prepareStartNanos = System.nanoTime();
  private long joinGameNanos;
  private long lastChunkPacketNanos;
  private int bufferedTargetPackets;
  private int chunkSizedPacketsSeen;
  private int droppedSourcePackets;
  private int suppressedLoadScreenEvents;

  /**
   * Creates a controller for one seamless switch attempt.
   *
   * @param server       the proxy instance
   * @param player       the player switching servers
   * @param target       the in-flight connection to the target server
   * @param resultFuture the connection request future to complete
   */
  public SeamlessSwitchController(VelocityServer server, ConnectedPlayer player,
      VelocityServerConnection target, CompletableFuture<Impl> resultFuture) {
    this.server = server;
    this.player = player;
    this.source = player.getConnectedServer();
    this.target = target;
    this.resultFuture = resultFuture;
    this.config = server.getConfiguration().getSeamlessTransfersConfig();
    if (!config.entityTrackingEnabled()) {
      logger.warn("Seamless transfer for {}: add-entity-packet-id / remove-entities-packet-id "
              + "are not configured; the previous server's entities cannot be cleaned up and "
              + "will linger as ghosts (risking entity id collisions)",
          player.getUsername());
    }
    this.prepareTimeoutTask = player.getConnection().eventLoop().schedule(this::onPrepareTimeout,
        config.prepareTimeoutMs(), TimeUnit.MILLISECONDS);
  }

  /**
   * Checks whether a connection attempt may use the seamless path.
   *
   * @param server the proxy instance
   * @param player the player switching servers
   * @param target the in-flight connection to the target server
   * @return true if a seamless switch should be attempted
   */
  public static boolean isEligible(VelocityServer server, ConnectedPlayer player,
      VelocityServerConnection target) {
    final SeamlessTransfersConfig config = server.getConfiguration().getSeamlessTransfersConfig();
    if (!config.enabled()) {
      return false;
    }
    if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
      // Older clients do not use the configuration state; the regular fast switch is already
      // reasonably smooth there and this controller's respawn strategy targets 1.20.2+.
      return false;
    }
    if (player.getConnection().getType() != ConnectionTypes.VANILLA) {
      // Modded clients may not tolerate a same-dimension respawn without a full reset.
      return false;
    }
    if (player.getSeamlessSwitchController() != null) {
      return false;
    }
    final VelocityServerConnection current = player.getConnectedServer();
    if (current == null || !current.hasCompletedJoin()) {
      return false;
    }
    return config.inSameGroup(current.getServerInfo().getName(),
        target.getServerInfo().getName());
  }

  public State getState() {
    return state;
  }

  public VelocityServerConnection getTarget() {
    return target;
  }

  public SeamlessTransfersConfig getConfig() {
    return config;
  }

  /**
   * Called when the target server sends its JoinGame packet: the configuration phase is over and
   * the snapshot buffering begins.
   *
   * @param joinGame the target server's JoinGame packet
   */
  void onTargetJoinGame(JoinGamePacket joinGame) {
    if (state != State.PREPARING) {
      return;
    }
    this.targetJoinGame = joinGame;
    this.state = State.BUFFERING;
    this.joinGameNanos = System.nanoTime();
    this.lastChunkPacketNanos = joinGameNanos;
    cancel(prepareTimeoutTask);
    prepareTimeoutTask = null;
    this.commitTimeoutTask = player.getConnection().eventLoop().schedule(
        () -> commit("timeout"), config.commitTimeoutMs(), TimeUnit.MILLISECONDS);
    scheduleGraceCheck(config.commitGraceMs());
  }

  /**
   * Buffers a decoded packet from the target server.
   *
   * @param packet the packet to buffer
   */
  void bufferPacket(MinecraftPacket packet) {
    if (state != State.BUFFERING && state != State.COMMITTING) {
      return;
    }
    // COMMITTING still buffers: packets decoded between the commit decision and the snapshot
    // flush would otherwise be lost. The flush drains the buffer after the connect event, so
    // late additions are included.
    ReferenceCountUtil.retain(packet);
    buffer.add(packet);
    bufferedTargetPackets++;
  }

  /**
   * Buffers an opaque (unparsed) packet from the target server and feeds the snapshot-completion
   * detection.
   *
   * @param buf the raw packet, including its packet id
   */
  void bufferUnknown(ByteBuf buf) {
    if (state != State.BUFFERING && state != State.COMMITTING) {
      return;
    }
    if (isLevelLoadStartEvent(buf)) {
      // The client never receives a world-switch packet during a hot-swap, so this game event
      // is the only thing that could make it display the "loading terrain" screen. The
      // snapshot delivers the chunks in the same flush — there is nothing to wait for, so the
      // event is dropped and no screen can appear.
      suppressedLoadScreenEvents++;
      return;
    }
    buffer.add(buf.retain());
    bufferedTargetPackets++;
    if (state != State.BUFFERING) {
      // Already committing; just collect for the flush.
      return;
    }
    if (buf.readableBytes() >= config.chunkPacketMinBytes()) {
      // Chunk data is orders of magnitude larger than the steady tick traffic (time updates,
      // entity movement). Tracking only these packets gives a version-independent signal,
      // which the all-packet quiet window cannot provide on a live server that never goes
      // quiet.
      chunkSizedPacketsSeen++;
      lastChunkPacketNanos = System.nanoTime();
      // Servers stream chunks closest to the player first, so the area that hides the loading
      // screen arrives early; the rest keeps flowing after the swap through the regular
      // forwarding path. Committing at a fixed chunk budget beats waiting for the whole
      // (batch-throttled) stream to end.
      if (config.commitChunkPackets() > 0
          && chunkSizedPacketsSeen >= config.commitChunkPackets()) {
        commit("chunks");
      }
    }
  }

  /** Called by the discard handler installed on the source connection during the swap window. */
  void onSourcePacketDropped() {
    droppedSourcePackets++;
  }

  private void scheduleGraceCheck(long delayMs) {
    graceCheckTask = player.getConnection().eventLoop().schedule(this::graceCheck,
        delayMs, TimeUnit.MILLISECONDS);
  }

  private void graceCheck() {
    if (state != State.BUFFERING) {
      return;
    }
    // A live server never goes fully quiet (time updates and entity movement arrive every tick),
    // so the quiet window only considers chunk-sized packets: once the initial chunk burst stops
    // for commitGraceMs, the snapshot is as complete as it will get.
    final long chunkQuietMs =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastChunkPacketNanos);
    if (chunkSizedPacketsSeen > 0 && chunkQuietMs >= config.commitGraceMs()) {
      commit("grace");
    } else {
      scheduleGraceCheck(Math.max(config.commitGraceMs() - chunkQuietMs, 20));
    }
  }

  private void onPrepareTimeout() {
    if (state == State.PREPARING) {
      abort(ConnectionRequestResults.forDisconnect(
          ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR, target.getServer()), null);
      logger.warn("Seamless transfer for {} to {} timed out during prepare; the player stays on "
          + "the current server", player.getUsername(), target.getServerInfo().getName());
    }
  }

  /**
   * Applies the buffered snapshot to the client: freezes the source connection, fires the connect
   * events and performs the same-dimension respawn + snapshot flush.
   *
   * @param trigger what caused the commit (for logging)
   */
  private void commit(String trigger) {
    if (state != State.BUFFERING || targetJoinGame == null) {
      return;
    }
    state = State.COMMITTING;
    cancel(commitTimeoutTask);
    cancel(graceCheckTask);
    commitTimeoutTask = null;
    graceCheckTask = null;

    final long commitStartNanos = System.nanoTime();
    final MinecraftConnection smc = target.ensureConnected();
    smc.setAutoReading(false);

    // Freeze the source: stop reading and discard anything already decoded. The old world is
    // about to be replaced; forwarding more source packets would corrupt the swapped state.
    final MinecraftConnection sourceMc = source != null ? source.getConnection() : null;
    if (sourceMc != null && !sourceMc.isClosed()) {
      sourceMc.setAutoReading(false);
      sourceMc.setActiveSessionHandler(sourceMc.getState(),
          new SeamlessDiscardSessionHandler(this, source));
    }

    server.getEventManager()
        .fire(new ServerConnectedEvent(player, target.getServer(),
            source != null ? source.getServer() : null))
        .thenRunAsync(() -> {
          if (!target.isActive() || !(player.getConnection()
              .getActiveSessionHandler() instanceof ClientPlaySessionHandler playHandler)) {
            // The swap window cannot be rolled back: the source connection is already frozen
            // and discarding. Fail cleanly instead of leaving the player in a zombie state.
            abort(ConnectionRequestResults.forDisconnect(
                ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR, target.getServer()), null);
            player.disconnect(ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR);
            return;
          }

          playHandler.doSeamlessSwitch(targetJoinGame, target, buffer);

          smc.setActiveSessionHandler(StateRegistry.PLAY,
              new BackendPlaySessionHandler(server, target));
          player.setConnectedServer(target);
          if (source != null) {
            source.disconnect();
          }
          smc.setAutoReading(true);

          state = State.LIVE;
          player.clearSeamlessSwitchController(this);
          server.getEventManager().fireAndForget(new ServerPostConnectEvent(player,
              source != null ? source.getServer() : null));
          resultFuture.complete(ConnectionRequestResults.successful(target.getServer()));

          if (config.verbose()) {
            final long now = System.nanoTime();
            logger.info("Seamless transfer for {} -> {} committed ({}): prepare={}ms, "
                    + "buffer={}ms, commit={}ms, bufferedTargetPackets={}, "
                    + "chunkSizedPackets={}, suppressedLoadScreenEvents={}, "
                    + "droppedSourcePackets={}",
                player.getUsername(), target.getServerInfo().getName(), trigger,
                TimeUnit.NANOSECONDS.toMillis(joinGameNanos - prepareStartNanos),
                TimeUnit.NANOSECONDS.toMillis(commitStartNanos - joinGameNanos),
                TimeUnit.NANOSECONDS.toMillis(now - commitStartNanos),
                bufferedTargetPackets, chunkSizedPacketsSeen,
                suppressedLoadScreenEvents, droppedSourcePackets);
          }
        }, smc.eventLoop()).exceptionally(exc -> {
          logger.error("Unable to seamlessly switch to new server {} for {}",
              target.getServerInfo().getName(), player.getUsername(), exc);
          player.disconnect(ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR);
          resultFuture.completeExceptionally(exc);
          return null;
        });
  }

  /**
   * Aborts the attempt before the commit: the buffered snapshot is released, the target
   * connection is closed and the player stays untouched on the source server.
   *
   * @param result the result to complete the connection request with, or null when {@code cause}
   *               is set
   * @param cause  optional exceptional completion
   */
  void abort(@Nullable Impl result, @Nullable Throwable cause) {
    if (state == State.LIVE || state == State.ABORTED) {
      return;
    }
    state = State.ABORTED;
    cancel(prepareTimeoutTask);
    cancel(commitTimeoutTask);
    cancel(graceCheckTask);
    releaseBuffer();
    player.clearSeamlessSwitchController(this);
    target.disconnect();
    if (cause != null) {
      resultFuture.completeExceptionally(cause);
    } else if (result != null) {
      resultFuture.complete(result);
    } else {
      resultFuture.complete(ConnectionRequestResults.forDisconnect(
          ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR, target.getServer()));
    }
  }

  /**
   * Marks the attempt as downgraded to a regular configuration-state switch. Unlike
   * {@link #abort}, the target connection and result future remain in use by the regular
   * switching flow.
   */
  void markDowngraded() {
    if (state == State.LIVE || state == State.ABORTED) {
      return;
    }
    state = State.ABORTED;
    cancel(prepareTimeoutTask);
    cancel(commitTimeoutTask);
    cancel(graceCheckTask);
    releaseBuffer();
    player.clearSeamlessSwitchController(this);
  }

  private void releaseBuffer() {
    Object msg;
    while ((msg = buffer.poll()) != null) {
      ReferenceCountUtil.release(msg);
    }
  }

  private static void cancel(@Nullable ScheduledFuture<?> task) {
    if (task != null) {
      task.cancel(false);
    }
  }

  /**
   * Fingerprints the "Game Event: start waiting for chunks" packet (event 13, value 0.0f)
   * without knowing its version-specific packet id: the payload is exactly [varint packet id]
   * [unsigned byte 13][float 0.0f]. The length, event byte and all-zero float together make
   * accidental matches on other packet types practically impossible.
   *
   * @param buf the raw packet, readerIndex at the packet id
   * @return true if this is the level-load-start game event
   */
  private static boolean isLevelLoadStartEvent(ByteBuf buf) {
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
   * Discards (and counts) everything still arriving from the source server during and after the
   * swap window. Installed right before the snapshot is applied; the source connection is
   * disconnected moments later.
   */
  private static final class SeamlessDiscardSessionHandler implements MinecraftSessionHandler {

    private final SeamlessSwitchController controller;
    private final VelocityServerConnection source;

    SeamlessDiscardSessionHandler(SeamlessSwitchController controller,
        VelocityServerConnection source) {
      this.controller = controller;
      this.source = source;
    }

    @Override
    public void handleGeneric(MinecraftPacket packet) {
      controller.onSourcePacketDropped();
    }

    @Override
    public void handleUnknown(ByteBuf buf) {
      controller.onSourcePacketDropped();
    }

    @Override
    public void disconnected() {
      source.getServer().removePlayer(source.getPlayer());
    }
  }
}
