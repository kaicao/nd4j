package org.nd4j.aeron.ipc;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.commons.lang3.tuple.Pair;
import org.bytedeco.javacpp.BytePointer;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.shape.Shape;
import org.nd4j.linalg.compression.CompressedDataBuffer;
import org.nd4j.linalg.compression.CompressionDescriptor;
import org.nd4j.linalg.factory.Nd4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * NDArray Serialization and
 * de serialization class for
 * aeron.
 *
 * This is a low level class
 * specifically meant for speed.
 *
 * @author Adam Gibson
 */
public class AeronNDArraySerde {


    /**
     * Returns the byte buffer size for the given
     * ndarray. This is an auxillary method
     * for determining the size of the buffer
     * size to allocate for sending an ndarray via
     * the aeron media driver.
     *
     * The math break down for uncompressed is:
     * 2 ints for rank of the array and an ordinal representing the data type of the data buffer
     * The rest is in order:
     * shape information
     * data buffer
     *
     * The math break down for compressed is:
     * 2 ints for rank and an ordinal representing the data type for the data buffer
     *
     * The rest is in order:
     * shape information
     * codec information
     * data buffer
     *
     * @param arr the array to compute the size for
     * @return the size of the byte buffer that was allocated
     */
    public static int byteBufferSizeFor(INDArray arr) {
        if (!arr.isCompressed()) {
            ByteBuffer buffer = arr.data().pointer().asByteBuffer().order(ByteOrder.nativeOrder());
            ByteBuffer shapeBuffer = arr.shapeInfoDataBuffer().pointer().asByteBuffer().order(ByteOrder.nativeOrder());
            //2 four byte ints at the beginning
            int twoInts = 8;
            return twoInts + buffer.limit() + shapeBuffer.limit();
        } else {
            CompressedDataBuffer compressedDataBuffer = (CompressedDataBuffer) arr.data();
            CompressionDescriptor descriptor = compressedDataBuffer.getCompressionDescriptor();
            ByteBuffer codecByteBuffer = descriptor.toByteBuffer();
            ByteBuffer buffer = arr.data().pointer().asByteBuffer().order(ByteOrder.nativeOrder());
            ByteBuffer shapeBuffer = arr.shapeInfoDataBuffer().pointer().asByteBuffer().order(ByteOrder.nativeOrder());
            int twoInts = 2 * 4;
            return twoInts + buffer.limit() + shapeBuffer.limit() + codecByteBuffer.limit();
        }
    }

    /**
     * Convert an ndarray to an unsafe buffer
     * for use by aeron
     * @param arr the array to convert
     * @return the unsafebuffer representation of this array
     */
    public static UnsafeBuffer toBuffer(INDArray arr) {
        //subset and get rid of 1 off non 1 element wise stride cases
        if (arr.isView())
            arr = arr.dup();
        if (!arr.isCompressed()) {
            ByteBuffer b3 = ByteBuffer.allocateDirect(byteBufferSizeFor(arr)).order(ByteOrder.nativeOrder());
            doByteBufferPutUnCompressed(arr, b3, true);
            return new UnsafeBuffer(b3);
        }
        //compressed array
        else {
            ByteBuffer b3 = ByteBuffer.allocateDirect(byteBufferSizeFor(arr)).order(ByteOrder.nativeOrder());
            doByteBufferPutCompressed(arr, b3, true);
            return new UnsafeBuffer(b3);
        }

    }


    /**
     * Setup the given byte buffer
     * for serialization (note that this is for uncompressed INDArrays)
     * 4 bytes int for rank
     * 4 bytes for data type
     * shape buffer
     * data buffer
     *
     * @param arr the array to setup
     * @param allocated the byte buffer to setup
     * @param rewind whether to rewind the byte buffer or nt
     */
    public static void doByteBufferPutUnCompressed(INDArray arr, ByteBuffer allocated, boolean rewind) {
        ByteBuffer buffer = arr.data().pointer().asByteBuffer().order(ByteOrder.nativeOrder());
        ByteBuffer shapeBuffer = arr.shapeInfoDataBuffer().pointer().asByteBuffer().order(ByteOrder.nativeOrder());
        //2 four byte ints at the beginning
        allocated.putInt(arr.rank());
        //put data type next so its self describing
        allocated.putInt(arr.data().dataType().ordinal());
        allocated.put(shapeBuffer);
        allocated.put(buffer);
        if (rewind)
            allocated.rewind();
    }

    /**
     * Setup the given byte buffer
     * for serialization (note that this is for compressed INDArrays)
     * 4 bytes for rank
     * 4 bytes for data type
     * shape information
     * codec information
     * data type
     *
     * @param arr the array to setup
     * @param allocated the byte buffer to setup
     * @param rewind whether to rewind the byte buffer or not
     */
    public static void doByteBufferPutCompressed(INDArray arr, ByteBuffer allocated, boolean rewind) {
        CompressedDataBuffer compressedDataBuffer = (CompressedDataBuffer) arr.data();
        CompressionDescriptor descriptor = compressedDataBuffer.getCompressionDescriptor();
        ByteBuffer codecByteBuffer = descriptor.toByteBuffer();
        ByteBuffer buffer = arr.data().pointer().asByteBuffer().order(ByteOrder.nativeOrder());
        ByteBuffer shapeBuffer = arr.shapeInfoDataBuffer().pointer().asByteBuffer().order(ByteOrder.nativeOrder());
        allocated.putInt(arr.rank());
        //put data type next so its self describing
        allocated.putInt(arr.data().dataType().ordinal());
        //put shape next
        allocated.put(shapeBuffer);
        //put codec information next
        allocated.put(codecByteBuffer);
        //finally put the data
        allocated.put(buffer);
        if (rewind)
            allocated.rewind();
    }


    /**
     * Create an ndarray
     * from the unsafe buffer.
     * Note that if you are interacting with a buffer that specifies
     * an {@link org.nd4j.aeron.ipc.NDArrayMessage.MessageType}
     * then you must pass in an offset + 4.
     * Adding 4 to the offset will cause the inter
     * @param buffer the buffer to create the array from
     * @return the ndarray derived from this buffer
     */
    public static Pair<INDArray, ByteBuffer> toArrayAndByteBuffer(DirectBuffer buffer, int offset) {
        ByteBuffer byteBuffer =
                        buffer.byteBuffer() == null
                                        ? ByteBuffer.allocateDirect(buffer.byteArray().length).put(buffer.byteArray())
                                                        .order(ByteOrder.nativeOrder())
                                        : buffer.byteBuffer().order(ByteOrder.nativeOrder());
        //bump the byte buffer to the proper position
        byteBuffer.position(offset);
        int rank = byteBuffer.getInt();
        if (rank < 0)
            throw new IllegalStateException("Found negative integer. Corrupt serialization?");
        //get the shape buffer length to create the shape information buffer
        int shapeBufferLength = Shape.shapeInfoLength(rank);
        //create the ndarray shape information
        DataBuffer shapeBuff = Nd4j.createBuffer(new int[shapeBufferLength]);

        //compute the databuffer type from the index
        DataBuffer.Type type = DataBuffer.Type.values()[byteBuffer.getInt()];
        for (int i = 0; i < shapeBufferLength; i++) {
            shapeBuff.put(i, byteBuffer.getInt());
        }

        //after the rank,data type, shape buffer (of length shape buffer length) * sizeof(int)
        if (type != DataBuffer.Type.COMPRESSED) {
            ByteBuffer slice = byteBuffer.slice();
            //wrap the data buffer for the last bit
            DataBuffer buff = Nd4j.createBuffer(slice, type, Shape.length(shapeBuff));
            //advance past the data
            int position = byteBuffer.position() + (buff.getElementSize() * (int) buff.length());
            byteBuffer.position(position);
            //create the final array
            //TODO: see how to avoid dup here
            INDArray arr = Nd4j.createArrayFromShapeBuffer(buff.dup(), shapeBuff.dup());
            return Pair.of(arr, byteBuffer);
        } else {
            CompressionDescriptor compressionDescriptor = CompressionDescriptor.fromByteBuffer(byteBuffer);
            ByteBuffer slice = byteBuffer.slice();
            //ensure that we only deal with the slice of the buffer that is actually the data
            BytePointer byteBufferPointer = new BytePointer(slice);
            //create a compressed array based on the rest of the data left in the buffer
            CompressedDataBuffer compressedDataBuffer =
                            new CompressedDataBuffer(byteBufferPointer, compressionDescriptor);
            //TODO: see how to avoid dup()
            INDArray arr = Nd4j.createArrayFromShapeBuffer(compressedDataBuffer.dup(), shapeBuff.dup());
            //advance past the data
            int compressLength = (int) compressionDescriptor.getCompressedLength();
            byteBuffer.position(byteBuffer.position() + compressLength);
            return Pair.of(arr, byteBuffer);
        }

    }


    /**
     * Create an ndarray
     * from the unsafe buffer
     * @param buffer the buffer to create the array from
     * @return the ndarray derived from this buffer
     */
    public static INDArray toArray(DirectBuffer buffer, int offset) {
        return toArrayAndByteBuffer(buffer, offset).getLeft();
    }

    /**
     * Create an ndarray
     * from the unsafe buffer
     * @param buffer the buffer to create the array from
     * @return the ndarray derived from this buffer
     */
    public static INDArray toArray(DirectBuffer buffer) {
        return toArray(buffer, 0);
    }



}
