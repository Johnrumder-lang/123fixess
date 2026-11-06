package lockon;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Same compact big-endian wire protocol as the authorized Paper companion:
 * int version, int count, and [byte nameLen, UTF8 name,
 * byte dimensionLen, UTF8 dimension, double x/y/z] for each position.
 */
public record LockOnPlayerPositionsPayload(
    List<LockOnPlayerPositionsPayload.Position> players)
    implements CustomPacketPayload {

    public record Position(String name, String dimension, Vec3 pos) {}

    public static final Type<LockOnPlayerPositionsPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("lockon", "players"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LockOnPlayerPositionsPayload> CODEC =
        StreamCodec.of(LockOnPlayerPositionsPayload::encode, LockOnPlayerPositionsPayload::decode);

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(1);
        buffer.writeInt(Math.min(players.size(), 120));
        for (int i = 0; i < Math.min(players.size(), 120); i++) {
            Position p = players.get(i);
            writeSmallText(buffer, p.name(), 16);
            writeSmallText(buffer, p.dimension(), 96);
            buffer.writeDouble(p.pos().x);
            buffer.writeDouble(p.pos().y);
            buffer.writeDouble(p.pos().z);
        }
    }

    private static LockOnPlayerPositionsPayload decode(RegistryFriendlyByteBuf buffer) {
        int version = buffer.readInt();
        int count = buffer.readInt();
        if (version != 1 || count < 0 || count > 120) {
            throw new IllegalArgumentException("Invalid LockOn positions packet");
        }
        List<Position> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = readSmallText(buffer, 16);
            String dimension = readSmallText(buffer, 96);
            double x = buffer.readDouble();
            double y = buffer.readDouble();
            double z = buffer.readDouble();
            if (name.matches("[A-Za-z0-9_]{1,16}")
                && dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]{1,90}")
                && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Math.abs(x) <= 30_000_000 && Math.abs(z) <= 30_000_000
                && Math.abs(y) < 5000) {
                positions.add(new Position(name, dimension, new Vec3(x,y,z)));
            }
        }
        return new LockOnPlayerPositionsPayload(List.copyOf(positions));
    }

    private static String readSmallText(RegistryFriendlyByteBuf buffer, int max) {
        int len = buffer.readUnsignedByte();
        if (len <= 0 || len > max) throw new IllegalArgumentException("Bad name length");
        byte[] bytes = new byte[len];
        buffer.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeSmallText(RegistryFriendlyByteBuf buffer, String value, int max) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > max) throw new IllegalArgumentException("Text too long");
        buffer.writeByte(bytes.length);
        buffer.writeBytes(bytes);
    }

    @Override
    public Type<LockOnPlayerPositionsPayload> type() {
        return TYPE;
    }
}
