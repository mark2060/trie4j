package org.trie4j.louds;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;

import org.junit.Assert;
import org.junit.Test;
import org.trie4j.Algorithms;
import org.trie4j.Node;
import org.trie4j.Trie;
import org.trie4j.TrieTestSet;
import org.trie4j.patricia.simple.PatriciaTrie;
import org.trie4j.tail.index.SBVTailIndex;

public class TailLOUDSPPTrieWithConcatTailBuilderTest extends TrieTestSet{
	@Override
	protected Trie trieWithWords(String... words) {
		Trie trie = new PatriciaTrie();
		for(String w : words) trie.insert(w);
		return new TailLOUDSPPTrie(trie);
	}

	@Test
	public void test() throws Exception{
		String[] words = {"こんにちは", "さようなら", "おはよう", "おおきなかぶ", "おおやまざき"};
		Trie trie = new PatriciaTrie();
		for(String w : words) trie.insert(w);
		
		final SBVTailIndex ti = new SBVTailIndex();
		TailLOUDSPPTrie lt = new TailLOUDSPPTrie(trie);
		System.out.println(lt.getBvTree());
		System.out.println(ti.getSBV());
		Algorithms.dump(lt.getRoot(), new OutputStreamWriter(System.out));
		for(String w : words){
			Assert.assertTrue(w, lt.contains(w));
		}
		Assert.assertFalse(lt.contains("おやすみなさい"));

		StringBuilder b = new StringBuilder();
		Node[] children = lt.getRoot().getChildren();
		for(Node n : children){
			char[] letters = n.getLetters();
			b.append(letters[0]);
		}
		Assert.assertEquals("おこさ", b.toString());
	}

	@Test
	public void test_save_load() throws Exception{
		String[] words = {"こんにちは", "さようなら", "おはよう", "おおきなかぶ", "おおやまざき"};
		Trie trie = new PatriciaTrie();
		for(String w : words) trie.insert(w);
		TailLOUDSPPTrie lt = new TailLOUDSPPTrie(trie);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		lt.save(baos);
		lt = new TailLOUDSPPTrie();
		lt.load(new ByteArrayInputStream(baos.toByteArray()));
		for(String w : words){
			Assert.assertTrue(lt.contains(w));
		}
		Assert.assertFalse(lt.contains("おやすみなさい"));

		StringBuilder b = new StringBuilder();
		Node[] children = lt.getRoot().getChildren();
		for(Node n : children){
			char[] letters = n.getLetters();
			b.append(letters[0]);
		}
		Assert.assertEquals("おこさ", b.toString());
	}
}