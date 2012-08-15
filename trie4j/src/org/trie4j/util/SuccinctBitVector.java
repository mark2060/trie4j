/*
 * Copyright 2012 Takao Nakaguchi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.trie4j.util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;

public class SuccinctBitVector implements Serializable{
	public SuccinctBitVector(){
		this(16);
	}

	public SuccinctBitVector(int initialCapacity){
		vector = new byte[initialCapacity / 8 + 1];
		int blockSize = CACHE_WIDTH;
		int size = initialCapacity / blockSize + 1;
		countCache0 = new int[size];
		indexCache0 = new int[size + 1];
	}

	@Override
	public String toString() {
		StringBuilder b = new StringBuilder();
		for(int i = 0; i < size; i++){
			b.append((vector[(i / 8)] & (0x80 >> (i % 8))) != 0 ? "1" : "0");
		}
		return b.toString();
	}

	public int size(){
		return this.size;
	}

	public void trimToSize(){
		int vectorSize = size / 8 + 1;
		byte[] nv = new byte[vectorSize];
		System.arraycopy(vector, 0, nv, 0, Math.min(vector.length, vectorSize));
		vector = nv;
		int blockSize = CACHE_WIDTH / 8;
		int size = vectorSize / blockSize + (((vectorSize % blockSize) != 0) ? 1 : 0);
		int countCacheSize0 = size;
		int[] ncc0 = new int[countCacheSize0];
		System.arraycopy(countCache0, 0, ncc0, 0, Math.min(countCache0.length, countCacheSize0));
		countCache0 = ncc0;
		int indexCacheSize = size + 1;
		int[] nic = new int[indexCacheSize];
		System.arraycopy(indexCache0, 0, nic, 0, Math.min(indexCache0.length, indexCacheSize));
		indexCache0 = nic;
	}

	public void append1(){
		int i = size / 8;
		int ci = size / CACHE_WIDTH;
		if(i >= vector.length){
			extend();
		}
		if(size % CACHE_WIDTH == 0 && ci > 0){
			countCache0[ci] = countCache0[ci - 1];
		}
		int r = size % 8;
		vector[i] |= BITS[r];
		size++;
	}

	public void append0(){
		int i = size / 8;
		int ci = size / CACHE_WIDTH;
		if(i >= vector.length){
			extend();
		}
		if(size % CACHE_WIDTH == 0 && ci > 0){
			countCache0[ci] = countCache0[ci - 1];
		}
		int r = size % 8;
		vector[i] &= ~BITS[r];
		size0++;
		switch(size0){
			case 1:
				node1pos = size;
				break;
			case 2:
				node2pos = size;
				break;
			case 3:
				node3pos = size;
				break;
		}
		if(size0 % CACHE_WIDTH == 0){
			indexCache0[size0 / CACHE_WIDTH] = size;
		}
		countCache0[ci]++;
		size++;
	}

	public void append(boolean bit){
		if(bit) append1();
		else append0();
	}

	public int rank1(int pos){
		int ret = 0;
		int cn = pos / CACHE_WIDTH;
		if(cn > 0){
			ret = cn * CACHE_WIDTH - countCache0[cn - 1];
		}
		int n = pos / 8;
		for(int i = (cn * (CACHE_WIDTH / 8)); i < n; i++){
			ret += BITCOUNTS1[vector[i] & 0xff];
		}
		ret += BITCOUNTS1[vector[n] & MASKS[pos % 8]];
		return ret;
	}

	public int rank0(int pos){
		int ret = 0;
		int cn = pos / CACHE_WIDTH;
		if(cn > 0){
			ret = countCache0[cn - 1];
		}
		int n = pos / 8;
		for(int i = (cn * (CACHE_WIDTH / 8)); i < n; i++){
			ret += BITCOUNTS0[vector[i] & 0xff];
		}
		ret += BITCOUNTS0[vector[n] & MASKS[pos % 8]] - 7 + (pos % 8);
		return ret;
	}

	public int rank(int pos, boolean b){
		if(b) return rank1(pos);
		else return rank0(pos);
	}

	public int select0_(int count){
		if(count <= 2){
			if(count == 1) return node1pos;
			else return node2pos;
		}
		int block = count / CACHE_WIDTH;
		int i = indexCache0[block] / 8;
		if(i > 0){
			count -= countCache0[(i - 1) / CACHE_WIDTH * 8];
		}
		if(count > 0){
			for(; i < vector.length; i++){
				if(i * 8 >= size) return -1;
				int c = BITCOUNTS0[vector[i] & 0xff];
				if(count <= c){
					int v = vector[i] & 0xff;
					for(int j = 0; j < 8; j++){
						if(i * 8 + j >= size) return -1;
						if((v & 0x80) == 0){
							count--;
							if(count == 0){
								return i * 8 + j;
							}
						}
						v <<= 1;
					}
				}
				count -= c;
			}
		} else{
			count--;
			i = Math.min(((i + 1) * CACHE_WIDTH) - 1, size - 1);
			int v = vector[i / 8] & 0xff;
			v >>= 8 - (i % 8) - 1;
			while(i >= 0){
				if((v & 0x01) == 0){
					count++;
					if(count == 0){
						return i;
					}
				}
				if(i % 8 == 0){
					v = vector[(i - 1) / 8] & 0xff;
				} else{
					v >>= 1;
				}
				i--;
			}		}
		return -1;
	}

	public int select0(int count){
		if(count > size) return -1;
		if(count <= 3){
			if(count == 1) return node1pos;
			else if(count == 2) return node2pos;
			else if(count == 3) return node3pos;
			else return -1;
		}
//*
		int idx = count / CACHE_WIDTH;
		int start = indexCache0[idx];
		if(count % CACHE_WIDTH == 0) return start;
		start /= CACHE_WIDTH;
		int end = 0;
		if(indexCache0.length > (idx + 1)){
			int c = (indexCache0[idx + 1]) / CACHE_WIDTH + 1;
			if(c != 1) end = c;
		}
		if(end == 0){
			int vectorSize = size / 8 + 1;
			int blockSize = CACHE_WIDTH / 8;
			end = vectorSize / blockSize + (((vectorSize % blockSize) != 0) ? 1 : 0);
		}
/*/
		int start = Math.max(offset / CACHE_WIDTH - 1, 0);
		int end = countCache.length;
//*/

		int m = 0;
		int d = 0;
		while(start != end){
			m = (start + end) / 2;
			d = count - countCache0[m];
			if(d < 0){
				end = m;
				continue;
			} else if(d > 0){
				if(start != m) start = m;
				else break;
			} else{
				break;
			}
		}
		if(d > 0){
			count = d;
		} else{
			while(m >= 0 && count <= countCache0[m]) m--;
			if(m >= 0) count -= countCache0[m];
		}

		int n = size / 8 + 1;
		for(int i = (m + 1) * CACHE_WIDTH / 8; i < n; i++){
			int bits = vector[i] & 0xff;
			int c = BITCOUNTS0[bits];
			if(count <= c){
				return i * 8 + BITPOS0[bits][count - 1];
			}
			count -= c;
		}
		return -1;
	}

	public int select1(int count){
		for(int i = 0; i < vector.length; i++){
			if(i * 8 >= size) return -1;
			int c = BITCOUNTS1[vector[i] & 0xff];
			if(count <= c){
				int v = vector[i] & 0xff;
				for(int j = 0; j < 8; j++){
					if(i * 8 + j >= size) return -1;
					if((v & 0x80) != 0){
						count--;
						if(count == 0){
							return i * 8 + j;
						}
					}
					v <<= 1;
				}
			}
			count -= c;
		}
		return -1;
	}

	public int select(int count, boolean b){
		if(b) return select1(count);
		else return select0(count);
	}

	public int next0(int pos){
		if(pos >= size) return -1;
		if(pos <= node3pos){
			if(pos <= node1pos) return node1pos;
			else if(pos <= node2pos) return node2pos;
			else return node3pos;
		}
		int i = pos / 8;
		int s = pos % 8;
		if(s != 0){
			for(byte b : BITPOS0[vector[i] & 0xff]){
				if(s <= b) return i * 8 + b;
			}
			i++;
		}
		int n = size / 8 + 1;
		for(; i < n; i++){
			byte[] poss = BITPOS0[vector[i] & 0xff];
			if(poss.length > 0){
				return poss[0] + i * 8;
			}
		}
		return -1;
	}

	public void save(OutputStream os) throws IOException{
		DataOutputStream dos = new DataOutputStream(os);
		dos.writeInt(size);
		dos.writeInt(size0);
		dos.writeInt(node1pos);
		dos.writeInt(node2pos);
		dos.writeInt(node3pos);
		trimToSize();
		dos.write(vector);
		for(int e : countCache0){
			dos.writeInt(e);
		}
		for(int e : indexCache0){
			dos.writeInt(e);
		}
	}

	public void load(InputStream is) throws IOException{
		DataInputStream dis = new DataInputStream(is);
		size = dis.readInt();
		size0 = dis.readInt();
		node1pos = dis.readInt();
		node2pos = dis.readInt();
		node3pos = dis.readInt();
		int vectorSize = size / 8 + 1;
		vector = new byte[vectorSize];
		dis.read(vector, 0, vectorSize);
		int blockSize = CACHE_WIDTH / 8;
		int size = vectorSize / blockSize + (((vectorSize % blockSize) != 0) ? 1 : 0);
		countCache0 = new int[size];
		for(int i = 0; i < size; i++){
			countCache0[i] = dis.readInt();
		}
		indexCache0 = new int[size + 1];
		for(int i = 0; i < size + 1; i++){
			indexCache0[i] = dis.readInt();
		}
	}

	private void writeObject(ObjectOutputStream s)
	throws IOException {
		trimToSize();
		ObjectOutputStream.PutField fields = s.putFields();
		fields.put("size", size);
		fields.put("size0", size0);
		fields.put("node1pos", node1pos);
		fields.put("node2pos", node2pos);
		trimToSize();
		fields.put("vector", vector);
		fields.put("countCache0", countCache0);
		fields.put("indexCache0", indexCache0);
		s.writeFields();
    }

	private void readObject(ObjectInputStream s)
	throws IOException, ClassNotFoundException {
		ObjectInputStream.GetField fields = s.readFields();
		size = fields.get("size", 0);
		size0 = fields.get("size0", 0);
		node1pos = fields.get("node1pos", -1);
		node2pos = fields.get("node2pos", -1);
		vector = (byte[])fields.get("vector", null);
		countCache0 = (int[])fields.get("countCache0", null);
		indexCache0 = (int[])fields.get("indexCache0", null);
    }

	private void extend(){
		int vectorSize = (int)(vector.length * 1.2) + 1;
		byte[] nv = new byte[vectorSize];
		System.arraycopy(vector, 0, nv, 0, vector.length);
		vector = nv;
		int blockSize = CACHE_WIDTH / 8;
		int size = vectorSize / blockSize + (((vectorSize % blockSize) != 0) ? 1 : 0);
		int[] nc0 = new int[size];
		System.arraycopy(countCache0, 0, nc0, 0, countCache0.length);
		countCache0 = nc0;
		int[] nic = new int[size + 1];
		System.arraycopy(indexCache0, 0, nic, 0, indexCache0.length);
		indexCache0 = nic;
	}

	private static final int CACHE_WIDTH = 64;
	private byte[] vector;
	private int node1pos = -1;
	private int node2pos = -1;
	private int node3pos = -1;
	private int size;
	private int size0;
	private int[] countCache0;
	private int[] indexCache0;

	private static final int[] MASKS = {
		0x80, 0xc0, 0xe0, 0xf0
		, 0xf8, 0xfc, 0xfe, 0xff
	};
	private static final byte[] BITS = {
		(byte)0x80, (byte)0x40, (byte)0x20, (byte)0x10
		, (byte)0x08, (byte)0x04, (byte)0x02, (byte)0x01
	};
	private static final byte[] BITCOUNTS1 = {
		0, 1, 1, 2, 1, 2, 2, 3, 1, 2, 2, 3, 2, 3, 3, 4,
		1, 2, 2, 3, 2, 3, 3, 4, 2, 3, 3, 4, 3, 4, 4, 5,
		1, 2, 2, 3, 2, 3, 3, 4, 2, 3, 3, 4, 3, 4, 4, 5,
		2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6,
		1, 2, 2, 3, 2, 3, 3, 4, 2, 3, 3, 4, 3, 4, 4, 5,
		2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6,
		2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6,
		3, 4, 4, 5, 4, 5, 5, 6, 4, 5, 5, 6, 5, 6, 6, 7,
		1, 2, 2, 3, 2, 3, 3, 4, 2, 3, 3, 4, 3, 4, 4, 5,
		2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6,
		2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6,
		3, 4, 4, 5, 4, 5, 5, 6, 4, 5, 5, 6, 5, 6, 6, 7,
		2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6,
		3, 4, 4, 5, 4, 5, 5, 6, 4, 5, 5, 6, 5, 6, 6, 7,
		3, 4, 4, 5, 4, 5, 5, 6, 4, 5, 5, 6, 5, 6, 6, 7,
		4, 5, 5, 6, 5, 6, 6, 7, 5, 6, 6, 7, 6, 7, 7, 8
	};
	private static final byte[] BITCOUNTS0 = {
		8, 7, 7, 6, 7, 6, 6, 5, 7, 6, 6, 5, 6, 5, 5, 4, 
		7, 6, 6, 5, 6, 5, 5, 4, 6, 5, 5, 4, 5, 4, 4, 3, 
		7, 6, 6, 5, 6, 5, 5, 4, 6, 5, 5, 4, 5, 4, 4, 3, 
		6, 5, 5, 4, 5, 4, 4, 3, 5, 4, 4, 3, 4, 3, 3, 2, 
		7, 6, 6, 5, 6, 5, 5, 4, 6, 5, 5, 4, 5, 4, 4, 3, 
		6, 5, 5, 4, 5, 4, 4, 3, 5, 4, 4, 3, 4, 3, 3, 2, 
		6, 5, 5, 4, 5, 4, 4, 3, 5, 4, 4, 3, 4, 3, 3, 2, 
		5, 4, 4, 3, 4, 3, 3, 2, 4, 3, 3, 2, 3, 2, 2, 1, 
		7, 6, 6, 5, 6, 5, 5, 4, 6, 5, 5, 4, 5, 4, 4, 3, 
		6, 5, 5, 4, 5, 4, 4, 3, 5, 4, 4, 3, 4, 3, 3, 2, 
		6, 5, 5, 4, 5, 4, 4, 3, 5, 4, 4, 3, 4, 3, 3, 2, 
		5, 4, 4, 3, 4, 3, 3, 2, 4, 3, 3, 2, 3, 2, 2, 1, 
		6, 5, 5, 4, 5, 4, 4, 3, 5, 4, 4, 3, 4, 3, 3, 2, 
		5, 4, 4, 3, 4, 3, 3, 2, 4, 3, 3, 2, 3, 2, 2, 1, 
		5, 4, 4, 3, 4, 3, 3, 2, 4, 3, 3, 2, 3, 2, 2, 1, 
		4, 3, 3, 2, 3, 2, 2, 1, 3, 2, 2, 1, 2, 1, 1, 0, 
	};
	public static void main(String[] args) throws Exception{
		System.out.println("\tprivate static final byte[][] BITPOS0 = {");
		for(int i = 0; i < 256; i++){
			int count = 0;
			System.out.print("\t\t{");
			for(int b = 0x80; b > 0; b >>= 1){
				if((i & b) == 0){
					System.out.print(count);
					System.out.print(", ");
				}
				count++;
			}
			System.out.println("}, // " + String.format("%d(%1$x)", i));
		}
		System.out.println("\t};");
	}
	private static final byte[][] BITPOS0 = {
		{0, 1, 2, 3, 4, 5, 6, 7, }, // 0(0)
		{0, 1, 2, 3, 4, 5, 6, }, // 1(1)
		{0, 1, 2, 3, 4, 5, 7, }, // 2(2)
		{0, 1, 2, 3, 4, 5, }, // 3(3)
		{0, 1, 2, 3, 4, 6, 7, }, // 4(4)
		{0, 1, 2, 3, 4, 6, }, // 5(5)
		{0, 1, 2, 3, 4, 7, }, // 6(6)
		{0, 1, 2, 3, 4, }, // 7(7)
		{0, 1, 2, 3, 5, 6, 7, }, // 8(8)
		{0, 1, 2, 3, 5, 6, }, // 9(9)
		{0, 1, 2, 3, 5, 7, }, // 10(a)
		{0, 1, 2, 3, 5, }, // 11(b)
		{0, 1, 2, 3, 6, 7, }, // 12(c)
		{0, 1, 2, 3, 6, }, // 13(d)
		{0, 1, 2, 3, 7, }, // 14(e)
		{0, 1, 2, 3, }, // 15(f)
		{0, 1, 2, 4, 5, 6, 7, }, // 16(10)
		{0, 1, 2, 4, 5, 6, }, // 17(11)
		{0, 1, 2, 4, 5, 7, }, // 18(12)
		{0, 1, 2, 4, 5, }, // 19(13)
		{0, 1, 2, 4, 6, 7, }, // 20(14)
		{0, 1, 2, 4, 6, }, // 21(15)
		{0, 1, 2, 4, 7, }, // 22(16)
		{0, 1, 2, 4, }, // 23(17)
		{0, 1, 2, 5, 6, 7, }, // 24(18)
		{0, 1, 2, 5, 6, }, // 25(19)
		{0, 1, 2, 5, 7, }, // 26(1a)
		{0, 1, 2, 5, }, // 27(1b)
		{0, 1, 2, 6, 7, }, // 28(1c)
		{0, 1, 2, 6, }, // 29(1d)
		{0, 1, 2, 7, }, // 30(1e)
		{0, 1, 2, }, // 31(1f)
		{0, 1, 3, 4, 5, 6, 7, }, // 32(20)
		{0, 1, 3, 4, 5, 6, }, // 33(21)
		{0, 1, 3, 4, 5, 7, }, // 34(22)
		{0, 1, 3, 4, 5, }, // 35(23)
		{0, 1, 3, 4, 6, 7, }, // 36(24)
		{0, 1, 3, 4, 6, }, // 37(25)
		{0, 1, 3, 4, 7, }, // 38(26)
		{0, 1, 3, 4, }, // 39(27)
		{0, 1, 3, 5, 6, 7, }, // 40(28)
		{0, 1, 3, 5, 6, }, // 41(29)
		{0, 1, 3, 5, 7, }, // 42(2a)
		{0, 1, 3, 5, }, // 43(2b)
		{0, 1, 3, 6, 7, }, // 44(2c)
		{0, 1, 3, 6, }, // 45(2d)
		{0, 1, 3, 7, }, // 46(2e)
		{0, 1, 3, }, // 47(2f)
		{0, 1, 4, 5, 6, 7, }, // 48(30)
		{0, 1, 4, 5, 6, }, // 49(31)
		{0, 1, 4, 5, 7, }, // 50(32)
		{0, 1, 4, 5, }, // 51(33)
		{0, 1, 4, 6, 7, }, // 52(34)
		{0, 1, 4, 6, }, // 53(35)
		{0, 1, 4, 7, }, // 54(36)
		{0, 1, 4, }, // 55(37)
		{0, 1, 5, 6, 7, }, // 56(38)
		{0, 1, 5, 6, }, // 57(39)
		{0, 1, 5, 7, }, // 58(3a)
		{0, 1, 5, }, // 59(3b)
		{0, 1, 6, 7, }, // 60(3c)
		{0, 1, 6, }, // 61(3d)
		{0, 1, 7, }, // 62(3e)
		{0, 1, }, // 63(3f)
		{0, 2, 3, 4, 5, 6, 7, }, // 64(40)
		{0, 2, 3, 4, 5, 6, }, // 65(41)
		{0, 2, 3, 4, 5, 7, }, // 66(42)
		{0, 2, 3, 4, 5, }, // 67(43)
		{0, 2, 3, 4, 6, 7, }, // 68(44)
		{0, 2, 3, 4, 6, }, // 69(45)
		{0, 2, 3, 4, 7, }, // 70(46)
		{0, 2, 3, 4, }, // 71(47)
		{0, 2, 3, 5, 6, 7, }, // 72(48)
		{0, 2, 3, 5, 6, }, // 73(49)
		{0, 2, 3, 5, 7, }, // 74(4a)
		{0, 2, 3, 5, }, // 75(4b)
		{0, 2, 3, 6, 7, }, // 76(4c)
		{0, 2, 3, 6, }, // 77(4d)
		{0, 2, 3, 7, }, // 78(4e)
		{0, 2, 3, }, // 79(4f)
		{0, 2, 4, 5, 6, 7, }, // 80(50)
		{0, 2, 4, 5, 6, }, // 81(51)
		{0, 2, 4, 5, 7, }, // 82(52)
		{0, 2, 4, 5, }, // 83(53)
		{0, 2, 4, 6, 7, }, // 84(54)
		{0, 2, 4, 6, }, // 85(55)
		{0, 2, 4, 7, }, // 86(56)
		{0, 2, 4, }, // 87(57)
		{0, 2, 5, 6, 7, }, // 88(58)
		{0, 2, 5, 6, }, // 89(59)
		{0, 2, 5, 7, }, // 90(5a)
		{0, 2, 5, }, // 91(5b)
		{0, 2, 6, 7, }, // 92(5c)
		{0, 2, 6, }, // 93(5d)
		{0, 2, 7, }, // 94(5e)
		{0, 2, }, // 95(5f)
		{0, 3, 4, 5, 6, 7, }, // 96(60)
		{0, 3, 4, 5, 6, }, // 97(61)
		{0, 3, 4, 5, 7, }, // 98(62)
		{0, 3, 4, 5, }, // 99(63)
		{0, 3, 4, 6, 7, }, // 100(64)
		{0, 3, 4, 6, }, // 101(65)
		{0, 3, 4, 7, }, // 102(66)
		{0, 3, 4, }, // 103(67)
		{0, 3, 5, 6, 7, }, // 104(68)
		{0, 3, 5, 6, }, // 105(69)
		{0, 3, 5, 7, }, // 106(6a)
		{0, 3, 5, }, // 107(6b)
		{0, 3, 6, 7, }, // 108(6c)
		{0, 3, 6, }, // 109(6d)
		{0, 3, 7, }, // 110(6e)
		{0, 3, }, // 111(6f)
		{0, 4, 5, 6, 7, }, // 112(70)
		{0, 4, 5, 6, }, // 113(71)
		{0, 4, 5, 7, }, // 114(72)
		{0, 4, 5, }, // 115(73)
		{0, 4, 6, 7, }, // 116(74)
		{0, 4, 6, }, // 117(75)
		{0, 4, 7, }, // 118(76)
		{0, 4, }, // 119(77)
		{0, 5, 6, 7, }, // 120(78)
		{0, 5, 6, }, // 121(79)
		{0, 5, 7, }, // 122(7a)
		{0, 5, }, // 123(7b)
		{0, 6, 7, }, // 124(7c)
		{0, 6, }, // 125(7d)
		{0, 7, }, // 126(7e)
		{0, }, // 127(7f)
		{1, 2, 3, 4, 5, 6, 7, }, // 128(80)
		{1, 2, 3, 4, 5, 6, }, // 129(81)
		{1, 2, 3, 4, 5, 7, }, // 130(82)
		{1, 2, 3, 4, 5, }, // 131(83)
		{1, 2, 3, 4, 6, 7, }, // 132(84)
		{1, 2, 3, 4, 6, }, // 133(85)
		{1, 2, 3, 4, 7, }, // 134(86)
		{1, 2, 3, 4, }, // 135(87)
		{1, 2, 3, 5, 6, 7, }, // 136(88)
		{1, 2, 3, 5, 6, }, // 137(89)
		{1, 2, 3, 5, 7, }, // 138(8a)
		{1, 2, 3, 5, }, // 139(8b)
		{1, 2, 3, 6, 7, }, // 140(8c)
		{1, 2, 3, 6, }, // 141(8d)
		{1, 2, 3, 7, }, // 142(8e)
		{1, 2, 3, }, // 143(8f)
		{1, 2, 4, 5, 6, 7, }, // 144(90)
		{1, 2, 4, 5, 6, }, // 145(91)
		{1, 2, 4, 5, 7, }, // 146(92)
		{1, 2, 4, 5, }, // 147(93)
		{1, 2, 4, 6, 7, }, // 148(94)
		{1, 2, 4, 6, }, // 149(95)
		{1, 2, 4, 7, }, // 150(96)
		{1, 2, 4, }, // 151(97)
		{1, 2, 5, 6, 7, }, // 152(98)
		{1, 2, 5, 6, }, // 153(99)
		{1, 2, 5, 7, }, // 154(9a)
		{1, 2, 5, }, // 155(9b)
		{1, 2, 6, 7, }, // 156(9c)
		{1, 2, 6, }, // 157(9d)
		{1, 2, 7, }, // 158(9e)
		{1, 2, }, // 159(9f)
		{1, 3, 4, 5, 6, 7, }, // 160(a0)
		{1, 3, 4, 5, 6, }, // 161(a1)
		{1, 3, 4, 5, 7, }, // 162(a2)
		{1, 3, 4, 5, }, // 163(a3)
		{1, 3, 4, 6, 7, }, // 164(a4)
		{1, 3, 4, 6, }, // 165(a5)
		{1, 3, 4, 7, }, // 166(a6)
		{1, 3, 4, }, // 167(a7)
		{1, 3, 5, 6, 7, }, // 168(a8)
		{1, 3, 5, 6, }, // 169(a9)
		{1, 3, 5, 7, }, // 170(aa)
		{1, 3, 5, }, // 171(ab)
		{1, 3, 6, 7, }, // 172(ac)
		{1, 3, 6, }, // 173(ad)
		{1, 3, 7, }, // 174(ae)
		{1, 3, }, // 175(af)
		{1, 4, 5, 6, 7, }, // 176(b0)
		{1, 4, 5, 6, }, // 177(b1)
		{1, 4, 5, 7, }, // 178(b2)
		{1, 4, 5, }, // 179(b3)
		{1, 4, 6, 7, }, // 180(b4)
		{1, 4, 6, }, // 181(b5)
		{1, 4, 7, }, // 182(b6)
		{1, 4, }, // 183(b7)
		{1, 5, 6, 7, }, // 184(b8)
		{1, 5, 6, }, // 185(b9)
		{1, 5, 7, }, // 186(ba)
		{1, 5, }, // 187(bb)
		{1, 6, 7, }, // 188(bc)
		{1, 6, }, // 189(bd)
		{1, 7, }, // 190(be)
		{1, }, // 191(bf)
		{2, 3, 4, 5, 6, 7, }, // 192(c0)
		{2, 3, 4, 5, 6, }, // 193(c1)
		{2, 3, 4, 5, 7, }, // 194(c2)
		{2, 3, 4, 5, }, // 195(c3)
		{2, 3, 4, 6, 7, }, // 196(c4)
		{2, 3, 4, 6, }, // 197(c5)
		{2, 3, 4, 7, }, // 198(c6)
		{2, 3, 4, }, // 199(c7)
		{2, 3, 5, 6, 7, }, // 200(c8)
		{2, 3, 5, 6, }, // 201(c9)
		{2, 3, 5, 7, }, // 202(ca)
		{2, 3, 5, }, // 203(cb)
		{2, 3, 6, 7, }, // 204(cc)
		{2, 3, 6, }, // 205(cd)
		{2, 3, 7, }, // 206(ce)
		{2, 3, }, // 207(cf)
		{2, 4, 5, 6, 7, }, // 208(d0)
		{2, 4, 5, 6, }, // 209(d1)
		{2, 4, 5, 7, }, // 210(d2)
		{2, 4, 5, }, // 211(d3)
		{2, 4, 6, 7, }, // 212(d4)
		{2, 4, 6, }, // 213(d5)
		{2, 4, 7, }, // 214(d6)
		{2, 4, }, // 215(d7)
		{2, 5, 6, 7, }, // 216(d8)
		{2, 5, 6, }, // 217(d9)
		{2, 5, 7, }, // 218(da)
		{2, 5, }, // 219(db)
		{2, 6, 7, }, // 220(dc)
		{2, 6, }, // 221(dd)
		{2, 7, }, // 222(de)
		{2, }, // 223(df)
		{3, 4, 5, 6, 7, }, // 224(e0)
		{3, 4, 5, 6, }, // 225(e1)
		{3, 4, 5, 7, }, // 226(e2)
		{3, 4, 5, }, // 227(e3)
		{3, 4, 6, 7, }, // 228(e4)
		{3, 4, 6, }, // 229(e5)
		{3, 4, 7, }, // 230(e6)
		{3, 4, }, // 231(e7)
		{3, 5, 6, 7, }, // 232(e8)
		{3, 5, 6, }, // 233(e9)
		{3, 5, 7, }, // 234(ea)
		{3, 5, }, // 235(eb)
		{3, 6, 7, }, // 236(ec)
		{3, 6, }, // 237(ed)
		{3, 7, }, // 238(ee)
		{3, }, // 239(ef)
		{4, 5, 6, 7, }, // 240(f0)
		{4, 5, 6, }, // 241(f1)
		{4, 5, 7, }, // 242(f2)
		{4, 5, }, // 243(f3)
		{4, 6, 7, }, // 244(f4)
		{4, 6, }, // 245(f5)
		{4, 7, }, // 246(f6)
		{4, }, // 247(f7)
		{5, 6, 7, }, // 248(f8)
		{5, 6, }, // 249(f9)
		{5, 7, }, // 250(fa)
		{5, }, // 251(fb)
		{6, 7, }, // 252(fc)
		{6, }, // 253(fd)
		{7, }, // 254(fe)
		{}, // 255(ff)
	};

	private static final long serialVersionUID = -7658605229245494623L;
}