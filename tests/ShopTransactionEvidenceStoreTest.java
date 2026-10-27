import de.baluserver.erzmarken.ShopTransactionEvidenceStore;
import java.nio.file.*;
import java.util.*;
public class ShopTransactionEvidenceStoreTest {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("evidence-test");
        String id=UUID.randomUUID().toString();
        Path j=root.resolve("data/erzmarken-shop-journal");Files.createDirectories(j);
        Files.writeString(j.resolve(id+".pending.json"), "test");
        EnumMap<ShopTransactionEvidenceStore.Source,Path> sources=new EnumMap<>(ShopTransactionEvidenceStore.Source.class);
        for(var s:ShopTransactionEvidenceStore.Source.values()) {
            Path file=root.resolve(s.name()+".txt");Files.writeString(file,s.name()+"=before");sources.put(s,file);
        }
        var before=ShopTransactionEvidenceStore.capture(root,id,ShopTransactionEvidenceStore.Moment.BEFORE,sources);
        check(before.files().size()==4);
        check(ShopTransactionEvidenceStore.verify(root,id,ShopTransactionEvidenceStore.Moment.BEFORE));
        try {ShopTransactionEvidenceStore.capture(root,id,ShopTransactionEvidenceStore.Moment.BEFORE,sources);throw new AssertionError("overwrite");}
        catch(FileAlreadyExistsException expected){}
        for (var file:sources.values()) Files.writeString(file,"after");
        ShopTransactionEvidenceStore.capture(root,id,ShopTransactionEvidenceStore.Moment.AFTER,sources);
        check(ShopTransactionEvidenceStore.verify(root,id,ShopTransactionEvidenceStore.Moment.AFTER));
        Path archived=root.resolve("data/erzmarken-shop-evidence").resolve(id).resolve("before/playerdata.bin");
        Files.writeString(archived,"tampered");
        check(!ShopTransactionEvidenceStore.verify(root,id,ShopTransactionEvidenceStore.Moment.BEFORE));
        check(ShopTransactionEvidenceStore.verify(root,id,ShopTransactionEvidenceStore.Moment.AFTER));
        String missing=UUID.randomUUID().toString();
        try {ShopTransactionEvidenceStore.capture(root,missing,ShopTransactionEvidenceStore.Moment.BEFORE,sources);throw new AssertionError("missing journal");}
        catch(java.io.IOException expected){}
        System.out.println("7 Evidence checks passed");
    }
    static void check(boolean b){if(!b)throw new AssertionError();}
}
