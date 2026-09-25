package com.naivework.basapkmaker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;

final class Der {
    private Der() {}
    static byte[] len(int n) throws IOException {
        if (n < 128) return new byte[]{(byte)n};
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int x=n, count=0;
        while(x>0){ count++; x>>>=8; }
        b.write(0x80|count);
        for(int i=count-1;i>=0;i--) b.write((n>>(8*i))&255);
        return b.toByteArray();
    }
    static byte[] tlv(int tag, byte[] value) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); b.write(tag); b.write(len(value.length)); b.write(value); return b.toByteArray();
    }
    static byte[] seq(byte[]... parts) throws IOException { return tlv(0x30,cat(parts)); }
    static byte[] set(byte[]... parts) throws IOException { return tlv(0x31,cat(parts)); }
    static byte[] explicit0(byte[] value) throws IOException { return tlv(0xA0,value); }
    static byte[] integer(BigInteger x) throws IOException { return tlv(0x02,x.toByteArray()); }
    static byte[] integer(long x) throws IOException { return integer(BigInteger.valueOf(x)); }
    static byte[] oid(int... arcs) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        b.write(arcs[0]*40+arcs[1]);
        for(int i=2;i<arcs.length;i++){
            int v=arcs[i]; byte[] tmp=new byte[5]; int p=tmp.length-1; tmp[p]=(byte)(v&0x7f); v>>>=7;
            while(v!=0){ tmp[--p]=(byte)((v&0x7f)|0x80); v>>>=7; }
            for(;p<tmp.length;p++) b.write(tmp[p]);
        }
        return tlv(0x06,b.toByteArray());
    }
    static byte[] nul() throws IOException { return new byte[]{0x05,0x00}; }
    static byte[] octet(byte[] v) throws IOException { return tlv(0x04,v); }
    static byte[] bitString(byte[] v) throws IOException { byte[] x=new byte[v.length+1]; System.arraycopy(v,0,x,1,v.length); return tlv(0x03,x); }
    static byte[] cat(byte[]... p){ ByteArrayOutputStream b=new ByteArrayOutputStream(); try{for(byte[] x:p)b.write(x);}catch(IOException e){throw new RuntimeException(e);} return b.toByteArray(); }
}
