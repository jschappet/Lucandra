/**
 * Copyright 2009 T Jake Luciani
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package lucandra;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.ColumnOrSuperColumn;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.ColumnPath;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.KeySlice;
import org.apache.cassandra.thrift.Mutation;
import org.apache.cassandra.thrift.NotFoundException;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.SliceRange;
import org.apache.cassandra.thrift.TimedOutException;
import org.apache.cassandra.thrift.UnavailableException;
import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.FieldInvertState;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.thrift.TException;

public class IndexWriter {

    private final String indexName;
    private final Cassandra.Iface client;
    private final ColumnPath docAllColumnPath;
    private boolean autoCommit;
    private Map<String,Map<String,List<Mutation>>> mutationMap;
    
    private static final Logger logger = Logger.getLogger(IndexWriter.class);

    public IndexWriter(String indexName, Cassandra.Iface client) {

        this.indexName = indexName;
        this.client = client;
        autoCommit  = true;
        docAllColumnPath = new ColumnPath(CassandraUtils.docColumnFamily);
        
        mutationMap = new HashMap<String,Map<String,List<Mutation>>>();
    }

    @SuppressWarnings("unchecked")
    public void addDocument(Document doc, Analyzer analyzer) throws CorruptIndexException, IOException {

        List<String> allIndexedTerms = new ArrayList<String>();
        
        
        //check for special field name
        String docId = doc.get(CassandraUtils.documentIdField);
 
        if(docId == null)
            docId = Long.toHexString((long) (System.nanoTime()+(Math.random()*System.nanoTime()))); 
        
        int position = 0;

        for (Fieldable field : (List<Fieldable>) doc.getFields()) {

            // Indexed field
            if (field.isIndexed() && field.isTokenized()) {

                TokenStream tokens = field.tokenStreamValue();

                if (tokens == null) {
                    tokens = analyzer.tokenStream(field.name(), new StringReader(field.stringValue()));
                }

                // collect term frequencies per doc
                Map<String, List<Integer>> termPositions = new HashMap<String, List<Integer>>();
                int lastOffset = 0;
                if (position > 0) {
                    position += analyzer.getPositionIncrementGap(field.name());
                }

                // Build the termPositions vector for all terms
	          
                tokens.reset(); // reset the TokenStream to the first token
                // set up token attributes we are working on
                PositionIncrementAttribute posIncrAttribute = tokens.addAttribute(PositionIncrementAttribute.class);
                TermAttribute termAttribute = tokens.addAttribute(TermAttribute.class);

                while (tokens.incrementToken()) {
                	String term = CassandraUtils.createColumnName(field.name(),
                			termAttribute.term());
                	allIndexedTerms.add(term);

                	List<Integer> pvec = termPositions.get(term);

                	if (pvec == null) {
                		pvec = new ArrayList<Integer>();
                		termPositions.put(term, pvec);
                	}

                	position += (posIncrAttribute.getPositionIncrement() - 1);
                	pvec.add(++position);
                }

                for (Map.Entry<String, List<Integer>> term : termPositions.entrySet()) {

                    // Terms are stored within a unique key combination
                    // This is required since cassandra loads all column
                    // families for a key into memory
                    String key = indexName + CassandraUtils.delimeter + term.getKey();
                    
                    CassandraUtils.addToMutationMap(mutationMap, CassandraUtils.termVecColumnFamily, docId.getBytes(), CassandraUtils.hashKey(key), CassandraUtils.intVectorToByteArray(term.getValue()));
                    
                }
            }

            //Untokenized fields go in without a termPosition
            if (field.isIndexed() && !field.isTokenized()) {
                String term = CassandraUtils.createColumnName(field.name(), field.stringValue());
                allIndexedTerms.add(term);
                
                String key = indexName + CassandraUtils.delimeter + term;

                CassandraUtils.addToMutationMap(mutationMap, CassandraUtils.termVecColumnFamily, docId.getBytes(), CassandraUtils.hashKey(key), CassandraUtils.intVectorToByteArray(Arrays.asList(new Integer[] { 0 })));
               
            }

            // Stores each field as a column under this doc key
            if (field.isStored()) {
                
                byte[] _value = field.isBinary() ? field.getBinaryValue() : field.stringValue().getBytes();         
                
                //first byte flags if binary or not
                byte[] value = new byte[_value.length+1];
                System.arraycopy(_value, 0, value, 0, _value.length);
                
                value[value.length-1] = (byte) (field.isBinary() ? Byte.MAX_VALUE : Byte.MIN_VALUE);
                
                String key = indexName+CassandraUtils.delimeter+docId;
                
                CassandraUtils.addToMutationMap(mutationMap, CassandraUtils.docColumnFamily, field.name().getBytes(), CassandraUtils.hashKey(key), value);
                            
            }
        }
        
        //Finally, Store meta-data so we can delete this document
        String key = indexName+CassandraUtils.delimeter+docId;
        
        CassandraUtils.addToMutationMap(mutationMap, CassandraUtils.docColumnFamily, CassandraUtils.documentMetaField.getBytes(), CassandraUtils.hashKey(key), CassandraUtils.toBytes(allIndexedTerms));
        
       
        
        if(autoCommit)
            CassandraUtils.robustBatchInsert(client, mutationMap);    
    }

    public void deleteDocuments(Query query) throws CorruptIndexException, IOException {
        
        IndexReader   reader   = new IndexReader(indexName,client);
        IndexSearcher searcher = new IndexSearcher(reader);
       
        TopDocs results = searcher.search(query,1000);
    
        for(int i=0; i<results.totalHits; i++){
            ScoreDoc doc = results.scoreDocs[i];
            
            
            String docId = reader.getDocumentId(doc.doc);
            try {
                deleteLucandraDocument(docId.getBytes());
            } catch (InvalidRequestException e) {
                throw new RuntimeException(e);
            } catch (NotFoundException e) {
                throw new RuntimeException(e);
            } catch (UnavailableException e) {
                throw new RuntimeException(e);
            } catch (TimedOutException e) {
                throw new RuntimeException(e);
            } catch (TException e) {
                throw new RuntimeException(e);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);       
            }
        }
        
    }
    
    @SuppressWarnings("unchecked")
    public void deleteDocuments(Term term) throws CorruptIndexException, IOException {
        try {
                       
            ColumnParent cp = new ColumnParent(CassandraUtils.termVecColumnFamily);
            String key = indexName+CassandraUtils.delimeter+CassandraUtils.createColumnName(term);
            
            List<ColumnOrSuperColumn> docs = client.get_slice(CassandraUtils.keySpace, CassandraUtils.hashKey(key), cp, new SlicePredicate().setSlice_range(new SliceRange(new byte[]{}, new byte[]{},true,Integer.MAX_VALUE)), ConsistencyLevel.ONE);
                
            //delete by documentId
            for(ColumnOrSuperColumn docInfo : docs){
                deleteLucandraDocument(docInfo.column.name);
            }
                              
        } catch (InvalidRequestException e) {
            throw new RuntimeException(e);
        } catch (UnavailableException e) {
            throw new RuntimeException(e);
        } catch (TException e) {
            throw new RuntimeException(e);
        } catch (TimedOutException e) {
            throw new RuntimeException(e);
        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }  
    }
    
    private void deleteLucandraDocument(byte[] docId) throws InvalidRequestException, NotFoundException, UnavailableException, TimedOutException, TException, IOException, ClassNotFoundException{

        String key =  indexName+CassandraUtils.delimeter+new String(docId);
        
        ColumnOrSuperColumn column = client.get(CassandraUtils.keySpace, CassandraUtils.hashKey(key), CassandraUtils.metaColumnPath, ConsistencyLevel.ONE);
        
        List<String> terms = (List<String>) CassandraUtils.fromBytes(column.column.value);
    
        for(String termStr : terms){
            
            key = indexName+CassandraUtils.delimeter+termStr;
            
            CassandraUtils.addToMutationMap(mutationMap, CassandraUtils.termVecColumnFamily, docId, CassandraUtils.hashKey(key), null);                                        
        }
    
        
        if(autoCommit)
            CassandraUtils.robustBatchInsert(client, mutationMap);
        
        //finally delete ourselves
        String selfKey = indexName+CassandraUtils.delimeter+new String(docId);
        
        
        //FIXME: once cassandra batch mutation supports slice predicates in deletions
        client.remove(CassandraUtils.keySpace, CassandraUtils.hashKey(selfKey), docAllColumnPath, System.currentTimeMillis(), ConsistencyLevel.ONE);

        
    }
    
    
    public void updateDocument(Term updateTerm, Document doc, Analyzer analyzer) throws CorruptIndexException, IOException{   
        
        deleteDocuments(updateTerm);
        addDocument(doc, analyzer);
        
    }

    public int docCount() {

        try{
            String start = CassandraUtils.hashKey(indexName + CassandraUtils.delimeter);
            String finish = start+CassandraUtils.delimeter;

            ColumnParent columnParent = new ColumnParent(CassandraUtils.docColumnFamily);
            SlicePredicate slicePredicate = new SlicePredicate();

            // Get all columns
            SliceRange sliceRange = new SliceRange(new byte[] {}, new byte[] {}, true, Integer.MAX_VALUE);
            slicePredicate.setSlice_range(sliceRange);

            List<KeySlice> columns  = client.get_range_slice(CassandraUtils.keySpace, columnParent, slicePredicate, start, finish, 5000, ConsistencyLevel.ONE);
        
            return columns.size();
            
        }catch(Exception e){
            throw new RuntimeException(e);
        }
       
    }

    public boolean isAutoCommit() {
        return autoCommit;
    }

    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }
    
    public void commit(){
        if(!autoCommit)
            CassandraUtils.robustBatchInsert(client, mutationMap);
    }

}
