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

package com.velocitypowered.proxy.protocol.packet.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

class ChatStateSeamlessBridgeTest {

  private static LastSeenMessages clientLastSeen(int offset) {
    final BitSet acknowledged = new BitSet();
    acknowledged.set(0, LastSeenMessages.WINDOW_SIZE); // the client saw a full window earlier
    return new LastSeenMessages(offset, acknowledged, (byte) 42);
  }

  @Test
  void withoutBridgeOffsetsPassThrough() {
    final ChatQueue.ChatState state = new ChatQueue.ChatState();
    final LastSeenMessages out = state.updateFromMessage(Instant.now(), clientLastSeen(3));
    assertNotNull(out);
    assertEquals(3, out.getOffset());
  }

  @Test
  void bridgeNeverAcknowledgesMessagesTheBackendDidNotSend() {
    final ChatQueue.ChatState state = new ChatQueue.ChatState();
    state.enterSeamlessBridge();

    // The client still counts 5 unacknowledged messages from the previous server.
    LastSeenMessages out = state.updateFromMessage(Instant.now(), clientLastSeen(5));
    assertNotNull(out);
    assertEquals(0, out.getOffset());
    assertTrue(out.getAcknowledged().isEmpty());

    // The new backend delivers two signed messages; the client acknowledges them (plus debt).
    state.onBackendSignedMessage();
    state.onBackendSignedMessage();
    out = state.updateFromMessage(Instant.now(), clientLastSeen(4));
    assertNotNull(out);
    assertEquals(2, out.getOffset());
    assertTrue(out.getAcknowledged().isEmpty());

    // Nothing pending any more.
    out = state.updateFromMessage(Instant.now(), clientLastSeen(1));
    assertNotNull(out);
    assertEquals(0, out.getOffset());
  }

  @Test
  void bridgeClampsAcknowledgementPackets() {
    final ChatQueue.ChatState state = new ChatQueue.ChatState();
    state.enterSeamlessBridge();
    assertEquals(0, state.accumulateAckCount(30));
    for (int i = 0; i < 3; i++) {
      state.onBackendSignedMessage();
    }
    assertEquals(2, state.accumulateAckCount(2));
    assertEquals(1, state.accumulateAckCount(10));
    assertTrue(state.createLastSeen().getAcknowledged().isEmpty());
  }
}
