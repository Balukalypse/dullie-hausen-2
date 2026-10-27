from pathlib import Path
import tempfile, subprocess
s=Path('/mnt/data/dullie_audit_work/src/main/java/de/baluserver/erzmarken')
with tempfile.TemporaryDirectory() as t:
 p=Path(t); package=p/'de/baluserver/erzmarken'; package.mkdir(parents=True)
 (package/'ShopState.java').write_text('package de.baluserver.erzmarken; public class ShopState { public record BuybackReceipt(String id,long timestampMillis,String playerUuid,String itemId,int quantity,long unitPrice,long paid,long walletAfter) {} }')
 g=p/'com/google/gson';g.mkdir(parents=True)
 (g/'Gson.java').write_text('package com.google.gson; public class Gson {public <T> T fromJson(String s,Class<T> c) {return null;} }')
 (package/'Test.java').write_text('''package de.baluserver.erzmarken;
 import java.util.*;
 public class Test {
 static void ok(boolean v){if(!v) throw new AssertionError("Failed");}
 public static void main(String[] a) {
 String id="123e4567-e89b-42d3-a456-426614174000",player="123e4567-e89b-42d3-a456-426614174001";
 var j=new ShopRecoveryContentMatch.Journal(id,player,"minecraft:stone",4,20,"SHOP_DEBITED",42);
 var r=new ShopRecoveryContentMatch.Receipt(id,player,"minecraft:stone",4,20);
 ok(ShopRecoveryContentMatch.compare(j,r,id)==ShopRecoveryContentMatch.Decision.MATCH);
 ok(ShopRecoveryContentMatch.compare(j,null,id)==ShopRecoveryContentMatch.Decision.MISSING_RECEIPT);
 ok(ShopRecoveryContentMatch.compare(j,r,"other")==ShopRecoveryContentMatch.Decision.INVALID_JOURNAL);
 ok(ShopRecoveryContentMatch.compare(j,new ShopRecoveryContentMatch.Receipt(id,player,"minecraft:dirt",4,20),id)==ShopRecoveryContentMatch.Decision.CONFLICT);
 ok(ShopRecoveryContentMatch.compare(j,new ShopRecoveryContentMatch.Receipt(id,player,"minecraft:stone",5,20),id)==ShopRecoveryContentMatch.Decision.CONFLICT);
 ok(ShopRecoveryContentMatch.compare(j,new ShopRecoveryContentMatch.Receipt(id,player,"minecraft:stone",4,21),id)==ShopRecoveryContentMatch.Decision.CONFLICT);
 ok(ShopRecoveryContentMatch.compare(new ShopRecoveryContentMatch.Journal(id,player,"minecraft:stone",0,20,"SHOP_DEBITED",42),r,id)==ShopRecoveryContentMatch.Decision.INVALID_JOURNAL);
 ok(ShopRecoveryContentMatch.compare(new ShopRecoveryContentMatch.Journal(id,player,"minecraft:stone",4,20,"WRONG",42),r,id)==ShopRecoveryContentMatch.Decision.INVALID_JOURNAL);
 ok(ShopRecoveryContentMatch.compare(new ShopRecoveryContentMatch.Journal(id,player,"minecraft:stone",4,20,"SHOP_DEBITED",0),r,id)==ShopRecoveryContentMatch.Decision.INVALID_JOURNAL);
 System.out.println("9 pure Java checks passed");
 }
 }''')
 files=[str(s/x) for x in ['ShopRecoveryContentAudit.java','ShopRecoveryContentMatch.java','ShopJournalAudit.java','ShopTransactionStages.java','ShopBuybackReceiptLink.java']]
 files += [str(z) for z in p.rglob('*.java')]
 cp=subprocess.run(['javac','-d',str(p/'out'),*files],capture_output=True,text=True)
 if cp.returncode: raise RuntimeError(cp.stderr)
 run=subprocess.run(['java','-cp',str(p/'out'),'de.baluserver.erzmarken.Test'],capture_output=True,text=True)
 if run.returncode: raise RuntimeError(run.stderr)
 print(run.stdout.strip());print('Recovery audit classes compiled with test-only Gson/ShopState stubs')
