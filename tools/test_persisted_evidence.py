from pathlib import Path
import tempfile, subprocess
src = Path(__file__).resolve().parents[1] / 'src/main/java/de/baluserver/erzmarken'
with tempfile.TemporaryDirectory() as temp:
    root=Path(temp); pkg=root/'de/baluserver/erzmarken';pkg.mkdir(parents=True)
    (pkg/'Test.java').write_text('''package de.baluserver.erzmarken;
import java.nio.file.*;import java.util.*;
public class Test {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  Path world=Files.createTempDirectory("evidence-test-");
  String id="123e4567-e89b-42d3-a456-426614174000";
  var report=new ShopJournalAudit.Report(List.of(new ShopJournalAudit.Evidence(id+".pending.json","",1,"PENDING")),1,0,true);
  var a=ShopPersistedEvidenceAudit.inspect(world,report,3);
  check(a.absent()==1&&a.invalid()==0);
  Path base=world.resolve("data/erzmarken-shop-evidence").resolve(id);Files.createDirectories(base.resolve("before"));
  var b=ShopPersistedEvidenceAudit.inspect(world,report,3);
  check(b.invalid()==1&&b.absent()==0); // Present without manifest is NOT absent
  Files.delete(base.resolve("before"));
  var c=ShopPersistedEvidenceAudit.inspect(world,report,3); check(c.absent()==1);
  Files.createDirectories(base.resolve("after"));
  var d=ShopPersistedEvidenceAudit.inspect(world,report,3);check(d.invalid()==1);
  Files.delete(base.resolve("after"));
  var noEntries=ShopPersistedEvidenceAudit.inspect(world,new ShopJournalAudit.Report(List.of(),0,0,false),3);
  check(noEntries.lines().size()==2);
  try {ShopPersistedEvidenceAudit.inspect(world,report,0);throw new AssertionError();}catch(IllegalArgumentException expected){}
  System.out.println("6 isolated evidence-audit checks passed");
 }
}''')
    files=[str(src/x) for x in ['ShopPersistedEvidenceAudit.java','ShopEvidencePresence.java','ShopTransactionEvidenceStore.java','ShopJournalAudit.java','ShopBuybackReceiptLink.java']]+[str(pkg/'Test.java')]
    r=subprocess.run(['javac','-d',str(root/'out'),*files],capture_output=True,text=True)
    if r.returncode:raise RuntimeError(r.stderr)
    r=subprocess.run(['java','-cp',str(root/'out'),'de.baluserver.erzmarken.Test'],capture_output=True,text=True)
    if r.returncode:raise RuntimeError(r.stderr)
    print(r.stdout.strip())
