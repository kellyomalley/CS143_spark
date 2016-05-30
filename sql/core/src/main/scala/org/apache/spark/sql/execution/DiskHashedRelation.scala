package org.apache.spark.sql.execution

import java.io._
import java.nio.file.{Path, StandardOpenOption, Files}
import java.util.{ArrayList => JavaArrayList}

import org.apache.spark.SparkException
import org.apache.spark.sql.catalyst.expressions.{Projection, Row}
import org.apache.spark.sql.execution.CS143Utils._

import scala.collection.JavaConverters._

/**
 * This trait represents a regular relation that is hash partitioned and spilled to
 * disk.
 */
private[sql] sealed trait DiskHashedRelation {
  /**
   *
   * @return an iterator of the [[DiskPartition]]s that make up this relation.
   */
  def getIterator(): Iterator[DiskPartition]

  /**
   * Close all the partitions for this relation. This should involve deleting the files hashed into.
   */
  def closeAllPartitions()
}

/**
 * A general implementation of [[DiskHashedRelation]].
 *
 * @param partitions the disk partitions that we are going to spill to
 */
protected [sql] final class GeneralDiskHashedRelation(partitions: Array[DiskPartition])
    extends DiskHashedRelation with Serializable {

  override def getIterator() = {
  	//can I just do this?
    partitions.toIterator
  }

  override def closeAllPartitions() = {
    //loop through all the partitions and close them
    for(part <- partitions)
    {
    	part.closePartition()
    }
  }
}

private[sql] class DiskPartition (
                                  filename: String,
                                  blockSize: Int) {
  private val path: Path = Files.createTempFile("", filename)
  private val data: JavaArrayList[Row] = new JavaArrayList[Row]
  private val outStream: OutputStream = Files.newOutputStream(path)
  private val inStream: InputStream = Files.newInputStream(path)
  private val chunkSizes: JavaArrayList[Int] = new JavaArrayList[Int]()
  private var writtenToDisk: Boolean = false
  private var inputClosed: Boolean = false

  /**
   * This method inserts a new row into this particular partition. If the size of the partition
   * exceeds the blockSize, the partition is spilled to disk.
   *
   * @param row the [[Row]] we are adding
   */
  def insert(row: Row) = {
    //first, inputClosed must be false because you can't write to a closed partition
    //see closeInput()
    if(inputClosed)
    {
    	throw new SparkException("Can't insert into closed partition!")
    }
    //find if the size of the partition exceeds the blockSize
    //first, find the size of the partition
    //use a temporary array to find size so we don't insert too big data by accident
    val spill: JavaArrayList[Row] = new JavaArrayList[Row]
    spill.add(row)

    //I think measurePartition size gives us the size of the data already there so we need to kinda
    //recreate that function and call the utility
    val size: Int = measurePartitionSize() + CS143Utils.getBytesFromList(spill).size
    if(size > blockSize)
    {
    	spillPartitionToDisk()
    	//make sure we don't add anything we didn't mean to
    	data.clear()
    }
    //actually insert
    data.add(row)
    //this was set to true in spillPartitionToDisk so return it to false
    writtenToDisk = false
  }

  /**
   * This method converts the data to a byte array and returns the size of the byte array
   * as an estimation of the size of the partition.
   *
   * @return the estimated size of the data
   */
  private[this] def measurePartitionSize(): Int = {
    CS143Utils.getBytesFromList(data).size
  }

  /**
   * Uses the [[Files]] API to write a byte array representing data to a file.
   */
  private[this] def spillPartitionToDisk() = {
    val bytes: Array[Byte] = getBytesFromList(data)

    // This array list stores the sizes of chunks written in order to read them back correctly.
    chunkSizes.add(bytes.size)

    Files.write(path, bytes, StandardOpenOption.APPEND)
    writtenToDisk = true
  }

  /**
   * If this partition has been closed, this method returns an Iterator of all the
   * data that was written to disk by this partition.
   *
   * @return the [[Iterator]] of the data
   */
  def getData(): Iterator[Row] = {
    if (!inputClosed) {
      throw new SparkException("Should not be reading from file before closing input. Bad things will happen!")
    }

    new Iterator[Row] {
      var currentIterator: Iterator[Row] = data.iterator.asScala
      val chunkSizeIterator: Iterator[Int] = chunkSizes.iterator().asScala
      var byteArray: Array[Byte] = null

      override def next() = {
      	//first just try to advance
      	if(currentIterator.hasNext)
      	{
      		currentIterator.next()
      	}
      	//need to read in the next chunk
      	//use fetch
      	else
      	{
      		//if we can get another chunk advance the iterator
      		if(fetchNextChunk())
      		{
      			//Converts an array of bytes into a JavaArrayList of type [[Row]]
      			//Then creates an iterator out of it
      			currentIterator = CS143Utils.getListFromBytes(byteArray).iterator.asScala
      			currentIterator.next()
      		}
      		//there's nothing we can do because we couldn't get another chunk
      		else
      		{
      			null
      		}
      	}
      }

      //this is just a bool kinda that look to see if a next exists
      override def hasNext() = {
      	//first just see if the current has a next
        if(currentIterator.hasNext)
        {
        	true
        }
        //chunkSizeIterator looks at the array chunkSize, so see if there's a next there
        else
        {
        	chunkSizeIterator.hasNext
        }
      }

      /**
       * Fetches the next chunk of the file and updates the iterator. Should return true
       * unless the iterator is empty.
       *
       * @return true unless the iterator is empty.
       */
      private[this] def fetchNextChunk(): Boolean = {
        //if there is a next chunk to fetch from, do it
        if(chunkSizeIterator.hasNext)
        {
        	//getNextChunkBytes has inputs: input stream, bytes to read, what we were reading from
        	CS143Utils.getNextChunkBytes(inStream, chunkSizeIterator.next(), byteArray)
        	true
        }
        else
        {
        	false
        }

      }
    }
  }

  /**
   * Closes this partition, implying that no more data will be written to this partition. If getData()
   * is called without closing the partition, an error will be thrown.
   *
   * If any data has not been written to disk yet, it should be written. The output stream should
   * also be closed.
   */
  def closeInput() = {
    //first, if any data has not been written to disk write it
    //remember we set it back to false after the insert
    if(!writtenToDisk)
    {
    	//if there is any data to write, do it then clear
    	if(data.size > 0)
    	{
    		spillPartitionToDisk()
    		data.clear()
    	}
    	//if there is no data then this should be true
    	else
    	{
    		writtenToDisk = true
    	}
    }
    //now, the output stream should be closed
    outStream.close()
    inputClosed = true
  }


  /**
   * Closes this partition. This closes the input stream and deletes the file backing the partition.
   */
  private[sql] def closePartition() = {
    inStream.close()
    Files.deleteIfExists(path)
  }
}

private[sql] object DiskHashedRelation {

  /**
   * Given an input iterator, partitions each row into one of a number of [[DiskPartition]]s
   * and constructors a [[DiskHashedRelation]].
   *
   * This executes the first phase of external hashing -- using a course-grained hash function
   * to partition the tuples to disk.
   *
   * The block size is approximately set to 64k because that is a good estimate of the average
   * buffer page.
   *
   * @param input the input [[Iterator]] of [[Row]]s
   * @param keyGenerator a [[Projection]] that generates the keys for the input
   * @param size the number of [[DiskPartition]]s
   * @param blockSize the threshold at which each partition will spill
   * @return the constructed [[DiskHashedRelation]]
   */
  def apply (
                input: Iterator[Row],
                keyGenerator: Projection,
                size: Int = 64,
                blockSize: Int = 64000) = {
    //first, make an array that has all the partitions in it
    val partitionArray: Array[DiskPartition] = new Array[DiskPartition](size)
    //now, fill the array with entries -- the number of disk partitions
    for(x <- 0 to size-1)
    {
    	//needs to be initialized with filename and blockSize
    	partitionArray(x) = new DiskPartition("partition_file_" + x.toString, blockSize)
    }

    //now iterate through the input and find hash values
    //insert them into the created partitionArray
    while(input.hasNext)
    {
    	val currRow: Row = input.next()
    	//spec says using the hash code function and modding by size is enough
    	val hashVal: Int = keyGenerator.apply(currRow).hashCode() % size
    	//insert nto the array at the position given by the hash
    	partitionArray(hashVal).insert(currRow)
    }

    //now hashing is done but need to clean up
    for(x <- 0 to size-1)
    {
    	partitionArray(x).closeInput
    }

    //create the DiskHashedRelation and return it
    val hashedRelation: DiskHashedRelation = new DiskHashedRelation(partitionArray)
    hashedRelation
  }
}
