import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Independent, minimal HPROF 1.0.2 writer used only to exercise the local Eclipse MAT
 * installation. Not derived from the Jafar test generator so it cannot borrow its assumptions.
 * Layout per https://openjdk.org/groups/hotspot/docs/hprof-format.txt.
 */
public final class MakeSyntheticHprof {
  private static final int ID = 8;

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]);
    ByteArrayOutputStream file = new ByteArrayOutputStream();
    DataOutputStream o = new DataOutputStream(file);
    o.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
    o.writeInt(ID);
    o.writeLong(0L); // dump timestamp
    utf8(o, 1, "agent/Probe");
    utf8(o, 2, "value");
    utf8(o, 3, "[B");
    utf8(o, 4, "bytes");
    loadClass(o, 1, 100, 1); // serial, class id, name id
    loadClass(o, 2, 200, 3); // byte[] class

    ByteArrayOutputStream heap = new ByteArrayOutputStream();
    DataOutputStream h = new DataOutputStream(heap);
    classDump(h, 100, 0, 16, new int[] {2}, new int[] {4}); // Probe { int value; }
    classDump(h, 200, 0, 16, new int[0], new int[0]); // [B, no fields

    for (int i = 0; i < 5; i++) {
      ByteArrayOutputStream data = new ByteArrayOutputStream();
      new DataOutputStream(data).writeInt(41 + i);
      instance(h, 1000 + i, 100, data.toByteArray());
    }
    // one 1 KiB byte[] so the histogram has a second row with real shallow size
    byte[] bytes = new byte[1024];
    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
    primArray(h, 2000, 8, bytes.length, bytes);

    h.writeByte(0x05); // ROOT_STICKY_CLASS
    h.writeLong(100);
    for (int i = 0; i < 5; i++) { // ROOT_UNKNOWN for each instance
      h.writeByte(0xFF);
      h.writeLong(1000 + i);
    }
    h.writeByte(0xFF);
    h.writeLong(2000);

    o.writeByte(0x1C); // HEAP_DUMP_SEGMENT
    o.writeInt(0);
    o.writeInt(heap.size());
    o.write(heap.toByteArray());
    o.writeByte(0x2C); // HEAP_DUMP_END
    o.writeInt(0);
    o.writeInt(0);
    o.flush();
    Files.write(out, file.toByteArray());
    System.out.println("wrote " + out + " (" + file.size() + " bytes)");
  }

  private static void utf8(DataOutputStream o, long id, String s) throws IOException {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    o.writeByte(0x01);
    o.writeInt(0);
    o.writeInt(ID + b.length);
    o.writeLong(id);
    o.write(b);
  }

  private static void loadClass(DataOutputStream o, int serial, long classId, long nameId)
      throws IOException {
    o.writeByte(0x02);
    o.writeInt(0);
    o.writeInt(4 + ID + 4 + ID);
    o.writeInt(serial);
    o.writeLong(classId);
    o.writeInt(serial); // stack trace serial (unused)
    o.writeLong(nameId);
  }

  private static void classDump(
      DataOutputStream o, long classId, long superId, int instanceSize, int[] names, int[] types)
      throws IOException {
    o.writeByte(0x20);
    o.writeLong(classId);
    o.writeInt(0); // stack trace
    o.writeLong(superId);
    o.writeLong(0); // loader
    o.writeLong(0); // signers
    o.writeLong(0); // protection domain
    o.writeLong(0); // reserved
    o.writeLong(0); // reserved
    o.writeInt(instanceSize);
    o.writeShort(0); // constant pool
    o.writeShort(0); // static fields
    o.writeShort(names.length);
    for (int i = 0; i < names.length; i++) {
      o.writeLong(names[i]);
      o.writeByte(types[i]);
    }
  }

  private static void instance(DataOutputStream o, long id, long classId, byte[] data)
      throws IOException {
    o.writeByte(0x21);
    o.writeLong(id);
    o.writeInt(0);
    o.writeLong(classId);
    o.writeInt(data.length);
    o.write(data);
  }

  private static void primArray(DataOutputStream o, long id, int elementType, int count, byte[] data)
      throws IOException {
    o.writeByte(0x23);
    o.writeLong(id);
    o.writeInt(0);
    o.writeInt(count);
    o.writeByte(elementType);
    o.write(data);
  }
}
