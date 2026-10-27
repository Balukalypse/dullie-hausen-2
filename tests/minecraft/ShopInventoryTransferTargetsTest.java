import java.io.InputStream;
import java.util.*;
import org.objectweb.asm.*;

/** Actual cached class descriptors and hook annotations, NOT Mixin application. */
public class ShopInventoryTransferTargetsTest {
    static int checks;
    static void check(boolean yes) { checks++; if (!yes) throw new AssertionError("check " + checks); }
    static Map<String, Integer> methods(String name) throws Exception {
        Map<String, Integer> methods = new HashMap<>();
        try (InputStream in = ShopInventoryTransferTargetsTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            check(in != null);
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String sig, String[] ex) {
                    methods.put(method + descriptor, access); return null;
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return methods;
    }
    public static void main(String[] args) throws Exception {
        var player = methods("net/minecraft/server/level/ServerPlayer");
        for (String key : List.of("drop(Z)V", "openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;",
                "openHorseInventory(Lnet/minecraft/world/entity/animal/equine/AbstractHorse;Lnet/minecraft/world/Container;)V",
                "openNautilusInventory(Lnet/minecraft/world/entity/animal/nautilus/AbstractNautilus;Lnet/minecraft/world/Container;)V"))
            check(player.containsKey(key) && (player.get(key) & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0);
        check(methods("net/minecraft/world/entity/item/ItemEntity")
            .containsKey("playerTouch(Lnet/minecraft/world/entity/player/Player;)V"));
        check(methods("net/minecraft/world/inventory/AbstractContainerMenu")
            .containsKey("clicked(IILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V"));
        check(methods("net/minecraft/world/inventory/AbstractCraftingMenu").containsKey(
            "handlePlacement(ZZLnet/minecraft/world/item/crafting/RecipeHolder;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/player/Inventory;)Lnet/minecraft/world/inventory/RecipeBookMenu$PostPlaceAction;"));
        for (String mixin : List.of("ShopInventoryPlayerTransferMixin", "ShopInventoryPickupMixin", "ShopInventoryMenuDropMixin", "ShopInventoryRecipePlacementMixin")) {
            try (InputStream in = ShopInventoryTransferTargetsTest.class.getClassLoader().getResourceAsStream(
                    "de/baluserver/erzmarken/mixin/" + mixin + ".class")) {
                check(in != null);
                new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] ex) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                                if (!desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    boolean cancellable, head;
                                    @Override public void visit(String key, Object value) {
                                        if (key.equals("cancellable")) cancellable = Boolean.TRUE.equals(value);
                                    }
                                    @Override public AnnotationVisitor visitArray(String key) {
                                        if (!key.equals("at")) return null;
                                        return new AnnotationVisitor(Opcodes.ASM9) {
                                            @Override public AnnotationVisitor visitAnnotation(String key, String desc) {
                                                return new AnnotationVisitor(Opcodes.ASM9) {
                                                    @Override public void visit(String key, Object value) {
                                                        if (key.equals("value")) head = value.equals("HEAD");
                                                    }
                                                };
                                            }
                                        };
                                    }
                                    @Override public void visitEnd() { check(cancellable); check(head); }
                                };
                            }
                        };
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        System.out.println("Transfer target/annotation checks: " + checks + " passed; no transformed classes/server run");
    }
}
