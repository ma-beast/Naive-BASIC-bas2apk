package com.naivework.basapkmaker;

import java.io.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.*;
import java.util.jar.JarFile;

/** Minimal historical-style Android V1/JAR signer. No V2/V3 signing block is emitted. */
public final class V1Signer {
    private static final String META = "META-INF/";
    private static final String SF = "META-INF/CERT.SF";
    private static final String RSA = "META-INF/CERT.RSA";
    private static final String BAS = "assets/programs/program.bas";

    private V1Signer() {}

    public static void make(File inputApk, File outputApk, byte[] program, String appLabel, String packageName, PrivateKey privateKey, X509Certificate cert) throws Exception {
        KeyMaterial km = new KeyMaterial(privateKey, cert);
        File tmp = new File(outputApk.getParent(), ".nwk_unsigned.apk");
        try {
            buildUnsigned(tmp, inputApk, program, appLabel, packageName);
            signJar(tmp, outputApk, km.privateKey, km.cert);
        } finally { if (tmp.exists()) tmp.delete(); }
    }

    private static void buildUnsigned(File out, File in, byte[] program, String appLabel, String packageName) throws Exception {
        ZipFile z = new ZipFile(in);
        ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)));
        zo.setLevel(9);
        byte[] buf=new byte[8192];
        try {
            Enumeration<? extends ZipEntry> en=z.entries();
            while(en.hasMoreElements()){
                ZipEntry e=en.nextElement(); String n=e.getName();
                if(n.startsWith(META)) continue;
                ZipEntry ne=new ZipEntry(n);
                ne.setTime(e.getTime());
                zo.putNextEntry(ne);
                if(BAS.equals(n)) zo.write(program);
                else if("AndroidManifest.xml".equals(n)) {
                    InputStream is=z.getInputStream(e);
                    ByteArrayOutputStream mb=new ByteArrayOutputStream();
                    int r;
                    while((r=is.read(buf))>0) mb.write(buf,0,r);
                    is.close();
                    zo.write(BinaryXmlLabel.setApplicationLabelAndPackage(mb.toByteArray(), appLabel, packageName));
                } else {
                    InputStream is=z.getInputStream(e); int r;
                    while((r=is.read(buf))>0) zo.write(buf,0,r);
                    is.close();
                }
                zo.closeEntry();
            }
        } finally { try{z.close();}catch(Exception ignored){} zo.close(); }
    }

    private static void signJar(File in, File out, PrivateKey key, X509Certificate cert) throws Exception {
        ZipFile z=new ZipFile(in);
        LinkedHashMap<String,byte[]> files=new LinkedHashMap<String,byte[]>();
        Enumeration<? extends ZipEntry> en=z.entries();
        byte[] buf=new byte[8192];
        while(en.hasMoreElements()){
            ZipEntry e=en.nextElement(); String n=e.getName();
            if(n.startsWith(META)) continue;
            ByteArrayOutputStream b=new ByteArrayOutputStream(); InputStream is=z.getInputStream(e); int r;
            while((r=is.read(buf))>0)b.write(buf,0,r); is.close(); files.put(n,b.toByteArray());
        }
        z.close();

        Manifest mf=new Manifest();
        Attributes main=mf.getMainAttributes();
        main.putValue("Manifest-Version","1.0");
        main.putValue("Created-By","1.0 (Android SignApk)");
        ArrayList<String> names=new ArrayList<String>(files.keySet()); Collections.sort(names);
        for(String n:names){
            Attributes a=new Attributes();
            a.putValue("SHA1-Digest", b64(sha1(files.get(n))));
            mf.getEntries().put(n,a);
        }
        byte[] mfBytes=manifestBytes(mf);
        StringBuilder sf=new StringBuilder();
        sf.append("Signature-Version: 1.0\r\n");
        sf.append("Created-By: 1.0 (Android SignApk)\r\n");
        sf.append("SHA1-Digest-Manifest: ").append(b64(sha1(mfBytes))).append("\r\n\r\n");
        for(String n:names){
            sf.append("Name: ").append(n).append("\r\n");
            sf.append("SHA1-Digest: ").append(b64(sha1(sectionBytes(n,files.get(n))))).append("\r\n\r\n");
        }
        byte[] sfBytes=sf.toString().getBytes("UTF-8");
        Signature sig=Signature.getInstance("SHA1withRSA"); sig.initSign(key); sig.update(sfBytes); byte[] signature=sig.sign();
        byte[] rsa=pkcs7(sfBytes, signature, cert);

        ZipOutputStream zo=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out))); zo.setLevel(9);
        write(zo,"META-INF/MANIFEST.MF",mfBytes); write(zo,SF,sfBytes); write(zo,RSA,rsa);
        for(String n:names) write(zo,n,files.get(n));
        zo.close();
    }

    private static byte[] manifestBytes(Manifest m)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();m.write(b);return b.toByteArray();}
    private static byte[] sectionBytes(String name, byte[] data)throws Exception{
        Attributes a=new Attributes(); a.putValue("Name",name); a.putValue("SHA1-Digest",b64(sha1(data)));
        Manifest m=new Manifest();m.getMainAttributes().putValue("Manifest-Version","1.0");m.getEntries().put(name,a);
        // java.util.jar.Manifest writes the Name section exactly as the Android verifier expects when hashing it.
        ByteArrayOutputStream b=new ByteArrayOutputStream(); m.write(b);
        byte[] all=b.toByteArray(); int p=indexOf(all,("Name: "+name+"\r\n").getBytes("UTF-8"));
        if(p<0) throw new IOException("manifest section not found");
        return Arrays.copyOfRange(all,p,all.length);
    }
    private static int indexOf(byte[] a,byte[] b){outer:for(int i=0;i<=a.length-b.length;i++){for(int j=0;j<b.length;j++)if(a[i+j]!=b[j])continue outer;return i;}return -1;}
    private static void write(ZipOutputStream z,String n,byte[] d)throws Exception{ZipEntry e=new ZipEntry(n);e.setTime(System.currentTimeMillis());z.putNextEntry(e);z.write(d);z.closeEntry();}
    private static byte[] sha1(byte[] d)throws Exception{return MessageDigest.getInstance("SHA-1").digest(d);}
    private static String b64(byte[] d){return android.util.Base64.encodeToString(d,android.util.Base64.NO_WRAP);}

    private static byte[] pkcs7(byte[] content, byte[] signature, X509Certificate cert)throws Exception{
        byte[] sha1Alg=Der.seq(Der.oid(1,3,14,3,2,26),Der.nul());
        byte[] rsaAlg=Der.seq(Der.oid(1,2,840,113549,1,1,1),Der.nul());
        byte[] issuer=cert.getIssuerX500Principal().getEncoded();
        byte[] issuerSerial=Der.seq(issuer,Der.integer(cert.getSerialNumber()));
        byte[] signerInfo=Der.seq(Der.integer(1),issuerSerial,sha1Alg,rsaAlg,Der.octet(signature));
        byte[] digestSet=Der.set(sha1Alg);
        byte[] contentInfo=Der.seq(Der.oid(1,2,840,113549,1,7,1));
        byte[] certSet=Der.explicit0(cert.getEncoded());
        byte[] signedData=Der.seq(Der.integer(1),digestSet,contentInfo,certSet,Der.set(signerInfo));
        return Der.seq(Der.oid(1,2,840,113549,1,7,2),Der.explicit0(signedData));
    }

    private static final class KeyMaterial{final PrivateKey privateKey;final X509Certificate cert;KeyMaterial(PrivateKey k,X509Certificate c){privateKey=k;cert=c;}}
}
