/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.usergrid.persistence.cassandra.index;


import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.apache.cassandra.utils.FBUtilities;

import org.apache.usergrid.persistence.cassandra.ApplicationCF;
import org.apache.usergrid.persistence.cassandra.CassandraService;

import com.google.common.primitives.UnsignedBytes;
import com.yammer.metrics.annotation.Metered;

import me.prettyprint.hector.api.beans.HColumn;

import static org.apache.usergrid.persistence.cassandra.CassandraPersistenceUtils.key;


/**
 * A simple class to make working with index buckets easier. Scans all buckets and merges the results into a single
 * column list to allow easy backwards compatibility with existing code
 *
 * @author tnine
 */
public class IndexBucketScanner<T> implements IndexScanner {

    private final CassandraService cass;
    private final UUID applicationId;
    private final Object keyPrefix;
    private final ApplicationCF columnFamily;
    private final Object finish;
    private final boolean reversed;
    private final int pageSize;
    private final boolean skipFirst;
    private final String bucket;
    private final StartToBytes<T> scanStartSerializer;

    /** Pointer to our next start read */
    private ByteBuffer start;

    /** Set to the original value to start scanning from */
    private T initialStartValue;

    /** Iterator for our results from the last page load */
    private List<HColumn<ByteBuffer, ByteBuffer>> lastResults;

    /** True if our last load loaded a full page size. */
    private boolean hasMore = true;



    public IndexBucketScanner( CassandraService cass, ApplicationCF columnFamily, StartToBytes<T> scanStartSerializer,
                               UUID applicationId, Object keyPrefix, String bucket,  T start, T finish,
                               boolean reversed, int pageSize, boolean skipFirst) {
        this.cass = cass;
        this.scanStartSerializer = scanStartSerializer;
        this.applicationId = applicationId;
        this.keyPrefix = keyPrefix;
        this.columnFamily = columnFamily;
        this.finish = finish;
        this.reversed = reversed;
        this.skipFirst = skipFirst;
        this.bucket = bucket;

        //we always add 1 to the page size.  This is because we pop the last column for the next page of results
        this.pageSize = pageSize+1;

        //the initial value set when we started scanning
        this.initialStartValue = start;
        this.start = scanStartSerializer.toBytes( initialStartValue );
    }


    /* (non-Javadoc)
     * @see org.apache.usergrid.persistence.cassandra.index.IndexScanner#reset()
     */
    @Override
    public void reset() {
        hasMore = true;
        start = scanStartSerializer.toBytes( initialStartValue );
    }


    /**
     * Search the collection index using all the buckets for the given collection. Load the next page. Return false if
     * nothing was loaded, true otherwise
     *
     * @return True if the data could be loaded
     */

    public boolean load() throws Exception {

        // nothing left to load
        if ( !hasMore ) {
            return false;
        }

       final Object rowKey =  key( keyPrefix, bucket );

        //if we skip the first we need to set the load to page size +2, since we'll discard the first
        //and start paging at the next entity, otherwise we'll just load the page size we need
        int selectSize = pageSize;

        //we purposefully use instance equality.  If it's a pointer to the same value, we need to increase by 1
        //since we'll be skipping the first value


        final boolean shouldCheckFirst =
                //we should skip the value it's a cursor resume OR it's a previous page from a stateful iterator
                (this.skipFirst &&  initialStartValue != null) || start != null;

        final List<HColumn<ByteBuffer, ByteBuffer>>
                resultsTree = cass.getColumns( cass.getApplicationKeyspace( applicationId ), columnFamily, rowKey,
                start, finish, selectSize, reversed );

        //remove the first element, it's from a cursor value and we don't want to retain it


        // we loaded a full page, there might be more
        if ( resultsTree.size() == selectSize ) {
            hasMore = true;

            // set the bytebuffer for the next pass
            start = resultsTree.get( resultsTree.size() - 1 ).getName();
        }
        else {
            hasMore = false;
        }

        //remove the first element since it needs to be skipped AFTER the size check. Otherwise it will fail
        //we only want to skip if our byte value are the same as our expected start.  Since we aren't stateful you can't
        //be sure your start even comes back, and you don't want to erroneously remove columns
        if ( shouldCheckFirst && resultsTree.size() > 0  && start != null) {
            final int startIndex = start.position();
            final int startLength = start.remaining();



            final ByteBuffer returnedBuffer = resultsTree.get( 0 ).getName();
            final int returnedIndex = returnedBuffer.position();
            final int returnedLength = returnedBuffer.remaining();


            final int compare = FBUtilities.compareUnsigned( start.array(), returnedBuffer.array(),  startIndex, returnedIndex, startLength, returnedLength ) ;

            //the byte buffers are the same as our seek (which may or may not be the case in the first seek)
            if(compare == 0){
                resultsTree.remove( 0 );
            }
        }

        lastResults = resultsTree;

        return lastResults != null && lastResults.size() > 0;
    }


    /**
     * Returns true if the 2 byte buffers contain the same bytes, false otherwise
     * @param first
     * @param second
     * @return
     */
    private boolean compareBuffer(final ByteBuffer first, final ByteBuffer second){
        int firstLength = first.remaining();
        int firstPosition = first.position();

        int secondLength = second.remaining();
        int secondPosition = second.position();

        final int compare = FBUtilities.compareUnsigned( first.array(), second.array(),  firstPosition, secondPosition, firstLength, secondLength);

        return compare == 0;


    }


    /*
     * (non-Javadoc)
     *
     * @see java.lang.Iterable#iterator()
     */
    @Override
    public Iterator<List<HColumn<ByteBuffer, ByteBuffer>>> iterator() {
        return this;
    }


    /*
     * (non-Javadoc)
     *
     * @see java.util.Iterator#hasNext()
     */
    @Override
    public boolean hasNext() {

        // We've either 1) paged everything we should and have 1 left from our
        // "next page" pointer
        // Our currently buffered results don't exist or don't have a next. Try to
        // load them again if they're less than the page size
        if ( lastResults == null && hasMore ) {
            try {
                return load();
            }
            catch ( Exception e ) {
                throw new RuntimeException( "Error loading next page of indexbucket scanner", e );
            }
        }

        return false;
    }


    /*
     * (non-Javadoc)
     *
     * @see java.util.Iterator#next()
     */
    @Override
    @Metered(group = "core", name = "IndexBucketScanner_load")
    public List<HColumn<ByteBuffer, ByteBuffer>> next() {
        List<HColumn<ByteBuffer, ByteBuffer>> returnVal = lastResults;

        lastResults = null;

        return returnVal;
    }


    /*
     * (non-Javadoc)
     *
     * @see java.util.Iterator#remove()
     */
    @Override
    public void remove() {
        throw new UnsupportedOperationException( "You can't remove from a result set, only advance" );
    }


    /* (non-Javadoc)
     * @see org.apache.usergrid.persistence.cassandra.index.IndexScanner#getPageSize()
     */
    @Override
    public int getPageSize() {
        return pageSize;
    }


    @Override
    public boolean isReversed() {
        return this.reversed;
    }

}
