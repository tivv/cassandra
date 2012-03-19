package org.apache.cassandra.hadoop;
/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */


import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.*;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import org.apache.commons.lang.ArrayUtils;

import org.apache.cassandra.auth.IAuthenticator;
import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.db.IColumn;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.TypeParser;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.thrift.*;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Pair;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;

public class ColumnFamilyRecordReader extends RecordReader<ByteBuffer, SortedMap<ByteBuffer, IColumn>>
    implements org.apache.hadoop.mapred.RecordReader<ByteBuffer, SortedMap<ByteBuffer, IColumn>>
{
    public static final int CASSANDRA_HADOOP_MAX_KEY_SIZE_DEFAULT = 8192;

    private ColumnFamilySplit split;
    private RowIterator iter;
    private Pair<ByteBuffer, SortedMap<ByteBuffer, IColumn>> currentRow;
    private SlicePredicate predicate;
    private boolean isEmptyPredicate;
    private int totalRowCount; // total number of rows to fetch
    private int batchSize; // fetch this many per batch
    private String cfName;
    private String keyspace;
    private TSocket socket;
    private Cassandra.Client client;
    private ConsistencyLevel consistencyLevel;
    private int keyBufferSize = 8192;
    private List<IndexExpression> filter;
    private boolean widerows;

    public ColumnFamilyRecordReader()
    {
        this(ColumnFamilyRecordReader.CASSANDRA_HADOOP_MAX_KEY_SIZE_DEFAULT);
    }

    public ColumnFamilyRecordReader(int keyBufferSize)
    {
        super();
        this.keyBufferSize = keyBufferSize;
    }

    public void close() 
    {
        if (socket != null && socket.isOpen())
        {
            socket.close();
            socket = null;
            client = null;
        }
    }
    
    public ByteBuffer getCurrentKey()
    {
        return currentRow.left;
    }

    public SortedMap<ByteBuffer, IColumn> getCurrentValue()
    {
        return currentRow.right;
    }
    
    public float getProgress()
    {
        // TODO this is totally broken for wide rows
        // the progress is likely to be reported slightly off the actual but close enough
        return ((float)iter.rowsRead()) / totalRowCount;
    }
    
    static boolean isEmptyPredicate(SlicePredicate predicate)
    {
        if (predicate == null)
            return true;
              
        if (predicate.isSetColumn_names() && predicate.getSlice_range() == null)
            return false;
        
        if (predicate.getSlice_range() == null)
            return true;
        
        byte[] start  = predicate.getSlice_range().getStart();
        byte[] finish = predicate.getSlice_range().getFinish(); 
        if ( (start == null || start == ArrayUtils.EMPTY_BYTE_ARRAY) &&
             (finish == null || finish == ArrayUtils.EMPTY_BYTE_ARRAY) )
            return true;
        
        
        return false;       
    }
    
    public void initialize(InputSplit split, TaskAttemptContext context) throws IOException
    {
        this.split = (ColumnFamilySplit) split;
        Configuration conf = context.getConfiguration();
        KeyRange jobRange = ConfigHelper.getInputKeyRange(conf);
        filter = jobRange == null ? null : jobRange.row_filter;
        predicate = ConfigHelper.getInputSlicePredicate(conf);
        widerows = ConfigHelper.getInputIsWide(conf);
        isEmptyPredicate = isEmptyPredicate(predicate);
        totalRowCount = ConfigHelper.getInputSplitSize(conf);
        batchSize = ConfigHelper.getRangeBatchSize(conf);
        cfName = ConfigHelper.getInputColumnFamily(conf);
        consistencyLevel = ConsistencyLevel.valueOf(ConfigHelper.getReadConsistencyLevel(conf));
        
        
        keyspace = ConfigHelper.getInputKeyspace(conf);
        
        try
        {
            // only need to connect once
            if (socket != null && socket.isOpen())
                return;

            // create connection using thrift
            String location = getLocation();
            socket = new TSocket(location, ConfigHelper.getInputRpcPort(conf));
            TBinaryProtocol binaryProtocol = new TBinaryProtocol(new TFramedTransport(socket));
            client = new Cassandra.Client(binaryProtocol);
            socket.open();

            // log in
            client.set_keyspace(keyspace);
            if (ConfigHelper.getInputKeyspaceUserName(conf) != null)
            {
                Map<String, String> creds = new HashMap<String, String>();
                creds.put(IAuthenticator.USERNAME_KEY, ConfigHelper.getInputKeyspaceUserName(conf));
                creds.put(IAuthenticator.PASSWORD_KEY, ConfigHelper.getInputKeyspacePassword(conf));
                AuthenticationRequest authRequest = new AuthenticationRequest(creds);
                client.login(authRequest);
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        iter = widerows ? new WideRowIterator() : new StaticRowIterator();
    }
    
    public boolean nextKeyValue() throws IOException
    {
        if (!iter.hasNext())
            return false;
        currentRow = iter.next();
        return true;
    }

    // we don't use endpointsnitch since we are trying to support hadoop nodes that are
    // not necessarily on Cassandra machines, too.  This should be adequate for single-DC clusters, at least.
    private String getLocation()
    {
        ArrayList<InetAddress> localAddresses = new ArrayList<InetAddress>();
        try
        {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements())
                localAddresses.addAll(Collections.list(nets.nextElement().getInetAddresses()));
        }
        catch (SocketException e)
        {
            throw new AssertionError(e);
        }

        for (InetAddress address : localAddresses)
        {
            for (String location : split.getLocations())
            {
                InetAddress locationAddress = null;
                try
                {
                    locationAddress = InetAddress.getByName(location);
                }
                catch (UnknownHostException e)
                {
                    throw new AssertionError(e);
                }
                if (address.equals(locationAddress))
                {
                    return location;
                }
            }
        }
        return split.getLocations()[0];
    }

    private abstract class RowIterator extends AbstractIterator<Pair<ByteBuffer, SortedMap<ByteBuffer, IColumn>>>
    {
        protected List<KeySlice> rows;
        protected KeySlice lastRow;
        protected int totalRead = 0;
        protected int i = 0;
        protected final AbstractType<?> comparator;
        protected final AbstractType<?> subComparator;
        protected final IPartitioner partitioner;

        private RowIterator()
        {
            try
            {
                partitioner = FBUtilities.newPartitioner(client.describe_partitioner());

                // Get the Keyspace metadata, then get the specific CF metadata
                // in order to populate the sub/comparator.
                KsDef ks_def = client.describe_keyspace(keyspace);
                List<String> cfnames = new ArrayList<String>();
                for (CfDef cfd : ks_def.cf_defs)
                    cfnames.add(cfd.name);
                int idx = cfnames.indexOf(cfName);
                CfDef cf_def = ks_def.cf_defs.get(idx);

                comparator = TypeParser.parse(cf_def.comparator_type);
                subComparator = cf_def.subcomparator_type == null ? null : TypeParser.parse(cf_def.subcomparator_type);
            }
            catch (ConfigurationException e)
            {
                throw new RuntimeException("unable to load sub/comparator", e);
            }
            catch (TException e)
            {
                throw new RuntimeException("error communicating via Thrift", e);
            }
            catch (Exception e)
            {
                throw new RuntimeException("unable to load keyspace " + keyspace, e);
            }
        }

        /**
         * @return total number of rows read by this record reader
         */
        public int rowsRead()
        {
            return totalRead;
        }

        protected IColumn unthriftify(ColumnOrSuperColumn cosc)
        {
            if (cosc.counter_column != null)
                return unthriftifyCounter(cosc.counter_column);
            if (cosc.counter_super_column != null)
                return unthriftifySuperCounter(cosc.counter_super_column);
            if (cosc.super_column != null)
                return unthriftifySuper(cosc.super_column);
            assert cosc.column != null;
            return unthriftifySimple(cosc.column);
        }

        private IColumn unthriftifySuper(SuperColumn super_column)
        {
            org.apache.cassandra.db.SuperColumn sc = new org.apache.cassandra.db.SuperColumn(super_column.name, subComparator);
            for (Column column : super_column.columns)
            {
                sc.addColumn(unthriftifySimple(column));
            }
            return sc;
        }

        private IColumn unthriftifySimple(Column column)
        {
            return new org.apache.cassandra.db.Column(column.name, column.value, column.timestamp);
        }

        private IColumn unthriftifyCounter(CounterColumn column)
        {
            //CounterColumns read the nodeID from the System table, so need the StorageService running and access
            //to cassandra.yaml. To avoid a Hadoop needing access to yaml return a regular Column.
            return new org.apache.cassandra.db.Column(column.name, ByteBufferUtil.bytes(column.value), 0);
        }

        private IColumn unthriftifySuperCounter(CounterSuperColumn superColumn)
        {
            org.apache.cassandra.db.SuperColumn sc = new org.apache.cassandra.db.SuperColumn(superColumn.name, subComparator);
            for (CounterColumn column : superColumn.columns)
                sc.addColumn(unthriftifyCounter(column));
            return sc;
        }
    }

    private class StaticRowIterator extends RowIterator
    {
        private void maybeInit()
        {
            // check if we need another batch
            if (rows != null && i >= rows.size())
                rows = null;

            if (rows != null)
                return;

            String startToken;
            if (lastRow == null)
            {
                startToken = split.getStartToken();
            }
            else
            {
                startToken = partitioner.getTokenFactory().toString(partitioner.getToken(lastRow.key));
                if (startToken.equals(split.getEndToken()))
                {
                    // reached end of the split
                    rows = null;
                    return;
                }
            }

            KeyRange keyRange = new KeyRange(batchSize)
                                .setStart_token(startToken)
                                .setEnd_token(split.getEndToken())
                                .setRow_filter(filter);
            try
            {
                rows = client.get_range_slices(new ColumnParent(cfName), predicate, keyRange, consistencyLevel);

                // nothing new? reached the end
                if (rows.isEmpty())
                {
                    rows = null;
                    return;
                }

                // prepare for the next slice to be read
                lastRow = Iterables.getLast(rows);

                // remove ghosts when fetching all columns
                if (isEmptyPredicate)
                {
                    Iterator<KeySlice> it = rows.iterator();
                    while (it.hasNext())
                    {
                        KeySlice ks = it.next();
                        if (ks.getColumnsSize() == 0)
                        {
                           it.remove();
                        }
                    }

                    // all ghosts, spooky
                    if (rows.isEmpty())
                    {
                        maybeInit();
                        return;
                    }
                }

                // reset to iterate through this new batch
                i = 0;
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        protected Pair<ByteBuffer, SortedMap<ByteBuffer, IColumn>> computeNext()
        {
            maybeInit();
            if (rows == null)
                return endOfData();

            totalRead++;
            KeySlice ks = rows.get(i++);
            SortedMap<ByteBuffer, IColumn> map = new TreeMap<ByteBuffer, IColumn>(comparator);
            for (ColumnOrSuperColumn cosc : ks.columns)
            {
                IColumn column = unthriftify(cosc);
                map.put(column.name(), column);
            }
            return new Pair<ByteBuffer, SortedMap<ByteBuffer, IColumn>>(ks.key, map);
        }
    }

    private class WideRowIterator extends RowIterator
    {
        private Iterator<ColumnOrSuperColumn> wideColumns;

        private void maybeInit()
        {
            if (wideColumns != null && wideColumns.hasNext())
                return;

            // check if we need another batch
            if (rows != null && ++i >= rows.size())
                rows = null;

            if (rows != null)
            {
                wideColumns = rows.get(i).columns.iterator();
                return;
            }

            String startToken;
            ByteBuffer startColumn;
            if (lastRow == null)
            {
                startToken = split.getStartToken();
                startColumn = ByteBufferUtil.EMPTY_BYTE_BUFFER;
            }
            else
            {
                startToken = partitioner.getTokenFactory().toString(partitioner.getToken(lastRow.key));
                startColumn = Iterables.getLast(lastRow.columns).column.name;
            }

            KeyRange keyRange = new KeyRange(batchSize)
                                .setStart_token(startToken)
                                .setEnd_token(split.getEndToken())
                                .setRow_filter(filter);
            try
            {
                rows = client.get_paged_slice(cfName, keyRange, startColumn, consistencyLevel);

                // nothing found?
                if (rows == null || rows.isEmpty() || rows.get(0).columns.isEmpty())
                {
                    rows = null;
                    return;
                }
                    
                // nothing new? reached the end
                if (lastRow != null && (rows.get(0).key.equals(lastRow.key) || rows.get(0).columns.get(0).column.name.equals(startColumn)))
                {
                    rows = null;
                    return;
                }

                // prepare for the next slice to be read
                lastRow = Iterables.getLast(rows);

                // reset to iterate through this new batch
                i = 0;
                wideColumns = rows.get(i).columns.iterator();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        protected Pair<ByteBuffer, SortedMap<ByteBuffer, IColumn>> computeNext()
        {
            maybeInit();
            if (rows == null)
                return endOfData();

            totalRead++;
            ColumnOrSuperColumn cosc = wideColumns.next();
            ImmutableSortedMap<ByteBuffer, IColumn> map = ImmutableSortedMap.of(cosc.column.name, unthriftify(cosc));
            return Pair.<ByteBuffer, SortedMap<ByteBuffer, IColumn>>create(rows.get(i).key, map);
        }
    }

    // Because the old Hadoop API wants us to write to the key and value
    // and the new asks for them, we need to copy the output of the new API
    // to the old. Thus, expect a small performance hit.
    // And obviously this wouldn't work for wide rows. But since ColumnFamilyInputFormat
    // and ColumnFamilyRecordReader don't support them, it should be fine for now.
    public boolean next(ByteBuffer key, SortedMap<ByteBuffer, IColumn> value) throws IOException
    {
        if (this.nextKeyValue())
        {
            key.clear();
            key.put(this.getCurrentKey());
            key.rewind();

            value.clear();
            value.putAll(this.getCurrentValue());

            return true;
        }
        return false;
    }

    public ByteBuffer createKey()
    {
        return ByteBuffer.wrap(new byte[this.keyBufferSize]);
    }

    public SortedMap<ByteBuffer, IColumn> createValue()
    {
        return new TreeMap<ByteBuffer, IColumn>();
    }

    public long getPos() throws IOException
    {
        return (long)iter.rowsRead();
    }
}
