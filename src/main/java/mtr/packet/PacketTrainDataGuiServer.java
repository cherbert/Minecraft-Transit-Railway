package mtr.packet;

import mtr.block.BlockRailwaySign;
import mtr.block.BlockRouteSignBase;
import mtr.data.*;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;
import java.util.function.Function;

public class PacketTrainDataGuiServer extends PacketTrainDataBase {

	private static final int PACKET_CHUNK_SIZE = (int) Math.pow(2, 14); // 16384
	private static final Map<Long, PacketByteBuf> TEMP_PACKETS_SENDER = new HashMap<>();

	public static void openDashboardScreenS2C(ServerPlayerEntity player) {
		final PacketByteBuf packet = PacketByteBufs.create();
		ServerPlayNetworking.send(player, PACKET_OPEN_DASHBOARD_SCREEN, packet);
	}

	public static void openRailwaySignScreenS2C(ServerPlayerEntity player, BlockPos signPos) {
		final PacketByteBuf packet = PacketByteBufs.create();
		packet.writeBlockPos(signPos);
		ServerPlayNetworking.send(player, PACKET_OPEN_RAILWAY_SIGN_SCREEN, packet);
	}

	public static void openTicketMachineScreenS2C(World world, ServerPlayerEntity player) {
		final PacketByteBuf packet = PacketByteBufs.create();
		packet.writeInt(TicketSystem.getPlayerScore(world, player, TicketSystem.BALANCE_OBJECTIVE).getScore());
		ServerPlayNetworking.send(player, PACKET_OPEN_TICKET_MACHINE_SCREEN, packet);
	}

	public static void createRailS2C(World world, BlockPos pos1, BlockPos pos2, Rail rail1, Rail rail2, long savedRailId) {
		final PacketByteBuf packet = PacketByteBufs.create();
		packet.writeBlockPos(pos1);
		packet.writeBlockPos(pos2);
		rail1.writePacket(packet);
		rail2.writePacket(packet);
		packet.writeLong(savedRailId);
		world.getPlayers().forEach(worldPlayer -> ServerPlayNetworking.send((ServerPlayerEntity) worldPlayer, PACKET_CREATE_RAIL, packet));
	}

	public static void removeNodeS2C(World world, BlockPos pos) {
		final PacketByteBuf packet = PacketByteBufs.create();
		packet.writeBlockPos(pos);
		world.getPlayers().forEach(worldPlayer -> ServerPlayNetworking.send((ServerPlayerEntity) worldPlayer, PACKET_REMOVE_NODE, packet));
	}

	public static void removeRailConnectionS2C(World world, BlockPos pos1, BlockPos pos2) {
		final PacketByteBuf packet = PacketByteBufs.create();
		packet.writeBlockPos(pos1);
		packet.writeBlockPos(pos2);
		world.getPlayers().forEach(worldPlayer -> ServerPlayNetworking.send((ServerPlayerEntity) worldPlayer, PACKET_REMOVE_RAIL, packet));
	}

	public static void sendAllInChunks(ServerPlayerEntity player, Set<Station> stations, Set<Platform> platforms, Set<Siding> sidings, Set<Route> routes, Set<Depot> depots, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		final long tempPacketId = new Random().nextLong();
		final PacketByteBuf packet = PacketByteBufs.create();

		serializeData(packet, stations);
		serializeData(packet, platforms);
		serializeData(packet, sidings);
		serializeData(packet, routes);
		serializeData(packet, depots);

		packet.writeInt(rails.size());
		rails.forEach((posStart, railMap) -> {
			packet.writeBlockPos(posStart);
			packet.writeInt(railMap.size());
			railMap.forEach((posEnd, rail) -> {
				packet.writeBlockPos(posEnd);
				rail.writePacket(packet);
			});
		});

		TEMP_PACKETS_SENDER.put(tempPacketId, packet);
		sendChunk(player, tempPacketId, 0);
	}

	public static <T extends NameColorDataBase> void receiveUpdateOrDeleteC2S(MinecraftServer minecraftServer, ServerPlayerEntity player, PacketByteBuf packet, Identifier packetId, Function<RailwayData, Set<T>> dataSet, Function<Long, T> createDataWithId, boolean isDelete) {
		final World world = player.world;
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (railwayData == null) {
			return;
		}

		final PacketCallback packetCallback = (updatePacket, fullPacket) -> {
			world.getPlayers().forEach(worldPlayer -> {
				if (!worldPlayer.getUuid().equals(player.getUuid())) {
					ServerPlayNetworking.send((ServerPlayerEntity) worldPlayer, packetId, fullPacket);
				}
			});
			railwayData.updateSidings();
		};

		if (isDelete) {
			deleteData(dataSet.apply(railwayData), minecraftServer, packet, packetCallback);
		} else {
			updateData(dataSet.apply(railwayData), minecraftServer, packet, packetCallback, createDataWithId);
		}

		if (packetId.equals(PACKET_UPDATE_STATION) || packetId.equals(PACKET_DELETE_STATION)) {
			try {
				UpdateBlueMap.updateBlueMap(world);
			} catch (NoClassDefFoundError ignored) {
				System.out.println("BlueMap is not loaded");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	public static void receiveSignIdsC2S(MinecraftServer minecraftServer, ServerPlayerEntity player, PacketByteBuf packet) {
		final BlockPos signPos = packet.readBlockPos();
		final int selectedIdsLength = packet.readInt();
		final Set<Long> selectedIds = new HashSet<>();
		for (int i = 0; i < selectedIdsLength; i++) {
			selectedIds.add(packet.readLong());
		}
		final int signLength = packet.readInt();
		final String[] signIds = new String[signLength];
		for (int i = 0; i < signLength; i++) {
			final String signId = packet.readString(SerializedDataBase.PACKET_STRING_READ_LENGTH);
			signIds[i] = signId.isEmpty() ? null : signId;
		}

		minecraftServer.execute(() -> {
			final BlockEntity entity = player.world.getBlockEntity(signPos);
			if (entity instanceof BlockRailwaySign.TileEntityRailwaySign) {
				((BlockRailwaySign.TileEntityRailwaySign) entity).setData(selectedIds, signIds);
			} else if (entity instanceof BlockRouteSignBase.TileEntityRouteSignBase) {
				((BlockRouteSignBase.TileEntityRouteSignBase) entity).setPlatformId(selectedIds.size() == 0 ? 0 : (long) selectedIds.toArray()[0]);
			}
		});
	}

	public static void receiveAddBalanceC2S(MinecraftServer minecraftServer, ServerPlayerEntity player, PacketByteBuf packet) {
		final int addAmount = packet.readInt();
		final int emeralds = packet.readInt();

		minecraftServer.execute(() -> {
			final World world = player.world;

			TicketSystem.addObjectivesIfMissing(world);
			ScoreboardPlayerScore balanceScore = TicketSystem.getPlayerScore(world, player, TicketSystem.BALANCE_OBJECTIVE);
			balanceScore.setScore(balanceScore.getScore() + addAmount);

			Inventories.remove(player.inventory, itemStack -> itemStack.getItem() == Items.EMERALD, emeralds, false);
			world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.BLOCKS, 1, 1);
		});
	}

	public static void handleResponseFromReceiver(ServerPlayerEntity player, PacketByteBuf packet) {
		final long tempPacketId = packet.readLong();
		final int chunk = packet.readInt();
		sendChunk(player, tempPacketId, chunk);
	}

	private static <T extends SerializedDataBase> void serializeData(PacketByteBuf packet, Set<T> objects) {
		packet.writeInt(objects.size());
		objects.forEach(object -> object.writePacket(packet));
	}

	private static void sendChunk(ServerPlayerEntity player, long tempPacketId, int chunk) {
		final PacketByteBuf packetChunk = PacketByteBufs.create();
		packetChunk.writeLong(tempPacketId);
		packetChunk.writeInt(chunk);

		final PacketByteBuf tempPacket = TEMP_PACKETS_SENDER.get(tempPacketId);
		if (chunk * PACKET_CHUNK_SIZE > tempPacket.readableBytes()) {
			TEMP_PACKETS_SENDER.remove(tempPacketId);
			packetChunk.writeBoolean(true);
		} else {
			packetChunk.writeBoolean(false);
			packetChunk.writeBytes(tempPacket.copy(chunk * PACKET_CHUNK_SIZE, Math.min(PACKET_CHUNK_SIZE, tempPacket.readableBytes() - chunk * PACKET_CHUNK_SIZE)));
		}

		try {
			ServerPlayNetworking.send(player, PACKET_CHUNK_S2C, packetChunk);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
