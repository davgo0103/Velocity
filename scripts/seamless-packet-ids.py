#!/usr/bin/env python3
"""Generates the packet id table of SeamlessPacketIds from ViaVersion's packet enums.

ViaVersion declares every protocol's packets as an enum whose ordinal is the packet id
(ClientboundPackets1_21_5, ServerboundPackets26_1, ...); a new enum only appears when the ids
change, so a Velocity version uses the newest enum at or below it.

Usage:
  git clone --depth 1 --filter=blob:none --sparse https://github.com/ViaVersion/ViaVersion.git vv
  git -C vv sparse-checkout set --no-cone '**/ClientboundPackets*.java' '**/ServerboundPackets*.java'
  python3 scripts/seamless-packet-ids.py vv

Paste the output between the GENERATED markers in SeamlessPacketIds.java. To support a new
Minecraft version, append it to VERSIONS (Velocity's ProtocolVersion suffix) and regenerate;
SeamlessPacketIdsTest fails the build while a supported version is missing from the table.
"""
import pathlib
import re
import sys

VERSIONS = ["1_20_2", "1_20_3", "1_20_5", "1_21", "1_21_2", "1_21_4", "1_21_5", "1_21_6",
            "1_21_7", "1_21_9", "1_21_11", "26_1", "26_2", "26_3"]

# Clientbound packets with a dedicated column, in record component order.
CLIENTBOUND_NAMED = ["ADD_ENTITY", "ADD_EXPERIENCE_ORB", "REMOVE_ENTITIES", "CHUNK_BATCH_FINISHED",
                     "GAME_EVENT", "PLAYER_CHAT", "ENTITY_EVENT", "SET_ENTITY_LINK",
                     "TAKE_ITEM_ENTITY", "SET_PASSENGERS"]
# Clientbound packets whose first field is a VarInt entity id.
CLIENTBOUND_LEADING_ID = ["ADD_ENTITY", "ADD_EXPERIENCE_ORB", "SET_ENTITY_DATA",
                          "UPDATE_ATTRIBUTES", "UPDATE_MOB_EFFECT", "REMOVE_MOB_EFFECT",
                          "SET_ENTITY_MOTION", "HURT_ANIMATION", "DAMAGE_EVENT",
                          "PLAYER_COMBAT_KILL", "SET_CAMERA", "MOVE_ENTITY_POS",
                          "MOVE_ENTITY_POS_ROT", "MOVE_ENTITY_ROT", "ROTATE_HEAD",
                          "TELEPORT_ENTITY", "ENTITY_POSITION_SYNC", "SET_EQUIPMENT"]
# Serverbound packets whose first field is a VarInt entity id.
SERVERBOUND_LEADING_ID = ["INTERACT", "ATTACK", "SPECTATE_ENTITY", "PLAYER_COMMAND",
                          "PICK_ITEM_FROM_ENTITY"]


def load(root, kind, version):
    path = next(root.rglob(f"{kind}Packets{version}.java"), None)
    if path is None:
        return None
    body = path.read_text(encoding="utf-8")
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    body = re.sub(r"//[^\n]*", "", body)
    constants = re.search(r"enum\s+\w+[^{]*\{(.*?);", body, re.S).group(1)
    names = [name.strip() for name in constants.split(",") if name.strip()]
    return {name: ordinal for ordinal, name in enumerate(names)}


def hex_id(table, name):
    return "0x%02X" % table[name] if name in table else "-1"


def ids(table, names):
    present = [hex_id(table, name) for name in names if name in table]
    lines = [", ".join(present[i:i + 9]) for i in range(0, len(present), 9)]
    return "ids(" + ",\n            ".join(lines) + ")"


def main():
    root = pathlib.Path(sys.argv[1])
    clientbound = serverbound = None
    for version in VERSIONS:
        clientbound = load(root, "Clientbound", version) or clientbound
        serverbound = load(root, "Serverbound", version) or serverbound
        named = ", ".join(hex_id(clientbound, name) for name in CLIENTBOUND_NAMED)
        print(f"    table(MINECRAFT_{version}, {named},")
        print(f"        {ids(clientbound, CLIENTBOUND_LEADING_ID)},")
        print(f"        {ids(serverbound, SERVERBOUND_LEADING_ID)});")


if __name__ == "__main__":
    main()
