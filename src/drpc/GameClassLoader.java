package drpc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Loads the original game classes, patching their bytecode on the way in.
 *
 * Patches:
 *  - Class.getResourceAsStream(String) -> Runtime.classResource(Class, String).
 *    On a phone every class reads from the MIDlet jar; the game even calls it on
 *    "".getClass(), which on desktop Java would look in the JDK and return null.
 *  - System.currentTimeMillis / Thread.sleep / Thread.yield -> GameClock (game speed, pause).
 *  - System.gc() removed: the game calls it on transitions, and a forced full GC causes visible stalls.
 *
 * Resources are handed out as fully buffered streams: phone VMs return whole reads/skips
 * from jar resources and the game relies on that, while a desktop inflater stream may not.
 */
final class GameClassLoader extends URLClassLoader {

    GameClassLoader(URL jar, ClassLoader parent) {
        super(new URL[] {jar}, parent);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] original;
        try (InputStream in = super.getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (in == null) {
                throw new ClassNotFoundException(name);
            }
            original = in.readAllBytes();
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        byte[] patched = patch(original);
        return defineClass(name, patched, 0, patched.length);
    }

    private static byte[] patch(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, exc)) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String mname, String mdesc, boolean itf) {
                        if (op == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/Class")
                                && mname.equals("getResourceAsStream")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "drpc/Runtime", "classResource",
                                    "(Ljava/lang/Class;Ljava/lang/String;)Ljava/io/InputStream;", false);
                            return;
                        }
                        if (op == Opcodes.INVOKESTATIC && owner.equals("java/lang/System") && mname.equals("gc")) {
                            return; // phones needed it; on desktop a forced full GC just stalls the game
                        }
                        if (op == Opcodes.INVOKESTATIC && owner.equals("java/lang/System")
                                && mname.equals("currentTimeMillis")) {
                            super.visitMethodInsn(op, "drpc/GameClock", "millis", mdesc, false);
                            return;
                        }
                        if (op == Opcodes.INVOKESTATIC && owner.equals("java/lang/Thread")
                                && (mname.equals("yield") || (mname.equals("sleep") && mdesc.equals("(J)V")))) {
                            super.visitMethodInsn(op, "drpc/GameClock", mname, mdesc, false);
                            return;
                        }
                        super.visitMethodInsn(op, owner, mname, mdesc, itf);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        InputStream in = super.getResourceAsStream(name);
        if (in == null) {
            System.out.println("[drpc] missing resource: " + name);
            return null;
        }
        try (in) {
            return new ByteArrayInputStream(in.readAllBytes());
        } catch (IOException e) {
            return null;
        }
    }
}
