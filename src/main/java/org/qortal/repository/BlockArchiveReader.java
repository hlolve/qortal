package org.qortal.repository;

import com.google.common.primitives.Ints;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.data.at.ATStateData;
import org.qortal.data.block.BlockArchiveData;
import org.qortal.data.block.BlockData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.settings.Settings;
import org.qortal.transform.TransformationException;
import org.qortal.transform.block.BlockTransformer;
import org.qortal.utils.Triple;

import static org.qortal.transform.Transformer.INT_LENGTH;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class BlockArchiveReader {

    private static BlockArchiveReader instance;
    private Map<String, Triple<Integer, Integer, Integer>> fileListCache;

    private static final Logger LOGGER = LogManager.getLogger(BlockArchiveReader.class);

    public BlockArchiveReader() {

    }

    public static synchronized BlockArchiveReader getInstance() {
        if (instance == null) {
            instance = new BlockArchiveReader();
        }

        return instance;
    }

    private void fetchFileList() {
        Path archivePath = Paths.get(Settings.getInstance().getRepositoryPath(), "archive").toAbsolutePath();
        File archiveDirFile = archivePath.toFile();
        String[] files = archiveDirFile.list();
        Map<String, Triple<Integer, Integer, Integer>> map = new HashMap<>();

        if (files != null) {
            for (String file : files) {
                Path filePath = Paths.get(file);
                String filename = filePath.getFileName().toString();

                // Parse the filename
                if (filename == null || !filename.contains("-") || !filename.contains(".")) {
                    // Not a usable file
                    continue;
                }
                // Remove the extension and split into two parts
                String[] parts = filename.substring(0, filename.lastIndexOf('.')).split("-");
                Integer startHeight = Integer.parseInt(parts[0]);
                Integer endHeight = Integer.parseInt(parts[1]);
                Integer range = endHeight - startHeight;
                map.put(filename, new Triple(startHeight, endHeight, range));
            }
        }
        this.fileListCache = Map.copyOf(map);
    }

    public Triple<BlockData, List<TransactionData>, List<ATStateData>> fetchBlockAtHeight(int height) {
        if (this.fileListCache == null) {
            this.fetchFileList();
        }

        byte[] serializedBytes = this.fetchSerializedBlockBytesForHeight(height);
        if (serializedBytes == null) {
            return null;
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(serializedBytes);
        Triple<BlockData, List<TransactionData>, List<ATStateData>> blockInfo = null;
        try {
            blockInfo = BlockTransformer.fromByteBuffer(byteBuffer);
            if (blockInfo != null && blockInfo.getA() != null) {
                // Block height is stored outside of the main serialized bytes, so it
                // won't be set automatically.
                blockInfo.getA().setHeight(height);
            }
        } catch (TransformationException e) {
            return null;
        }
        return blockInfo;
    }

    public Triple<BlockData, List<TransactionData>, List<ATStateData>> fetchBlockWithSignature(
            byte[] signature, Repository repository) {

        if (this.fileListCache == null) {
            this.fetchFileList();
        }

        Integer height = this.fetchHeightForSignature(signature, repository);
        if (height != null) {
            return this.fetchBlockAtHeight(height);
        }
        return null;
    }

    public List<Triple<BlockData, List<TransactionData>, List<ATStateData>>> fetchBlocksFromRange(
            int startHeight, int endHeight) {

        List<Triple<BlockData, List<TransactionData>, List<ATStateData>>> blockInfoList = new ArrayList<>();

        for (int height = startHeight; height <= endHeight; height++) {
            Triple<BlockData, List<TransactionData>, List<ATStateData>> blockInfo = this.fetchBlockAtHeight(height);
            if (blockInfo == null) {
                return blockInfoList;
            }
            blockInfoList.add(blockInfo);
        }
        return blockInfoList;
    }

    public Integer fetchHeightForSignature(byte[] signature, Repository repository) {
        // Lookup the height for the requested signature
        try {
            BlockArchiveData archivedBlock = repository.getBlockArchiveRepository().getBlockArchiveDataForSignature(signature);
            if (archivedBlock == null) {
                return null;
            }
            return archivedBlock.getHeight();

        } catch (DataException e) {
            return null;
        }
    }

    public int fetchHeightForTimestamp(long timestamp, Repository repository) {
        // Lookup the height for the requested signature
        try {
            return repository.getBlockArchiveRepository().getHeightFromTimestamp(timestamp);

        } catch (DataException e) {
            return 0;
        }
    }

    private String getFilenameForHeight(int height) {
        if (this.fileListCache == null) {
            this.fetchFileList();
        }

        Iterator it = this.fileListCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry) it.next();
            if (pair == null && pair.getKey() == null && pair.getValue() == null) {
                continue;
            }
            Triple<Integer, Integer, Integer> heightInfo = (Triple<Integer, Integer, Integer>) pair.getValue();
            Integer startHeight = heightInfo.getA();
            Integer endHeight = heightInfo.getB();

            if (height >= startHeight && height <= endHeight) {
                // Found the correct file
                String filename = (String) pair.getKey();
                return filename;
            }
        }

        return null;
    }

    public byte[] fetchSerializedBlockBytesForSignature(byte[] signature, boolean includeHeightPrefix, Repository repository) {
        if (this.fileListCache == null) {
            this.fetchFileList();
        }

        Integer height = this.fetchHeightForSignature(signature, repository);
        if (height != null) {
            byte[] blockBytes = this.fetchSerializedBlockBytesForHeight(height);
            if (blockBytes == null) {
                return null;
            }

            // When responding to a peer with a BLOCK message, we must prefix the byte array with the block height
            // This mimics the toData() method in BlockMessage and CachedBlockMessage
            if (includeHeightPrefix) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(blockBytes.length + INT_LENGTH);
                try {
                    bytes.write(Ints.toByteArray(height));
                    bytes.write(blockBytes);
                    return bytes.toByteArray();

                } catch (IOException e) {
                    return null;
                }
            }
            return blockBytes;
        }
        return null;
    }

    public byte[] fetchSerializedBlockBytesForHeight(int height) {
        String filename = this.getFilenameForHeight(height);
        if (filename == null) {
            // We don't have this block in the archive
            // Invalidate the file list cache in case it is out of date
            this.invalidateFileListCache();
            return null;
        }

        Path filePath = Paths.get(Settings.getInstance().getRepositoryPath(), "archive", filename).toAbsolutePath();
        RandomAccessFile file = null;
        try {
            file = new RandomAccessFile(filePath.toString(), "r");
            // Get info about this file (the "fixed length header")
            final int version = file.readInt(); // Do not remove or comment out, as it is moving the file pointer
            final int startHeight = file.readInt(); // Do not remove or comment out, as it is moving the file pointer
            final int endHeight = file.readInt(); // Do not remove or comment out, as it is moving the file pointer
            file.readInt(); // Block count (unused) // Do not remove or comment out, as it is moving the file pointer
            final int variableHeaderLength = file.readInt(); // Do not remove or comment out, as it is moving the file pointer
            final int fixedHeaderLength = (int)file.getFilePointer();
            // End of fixed length header

            // Make sure the version is one we recognize
            if (version != 1) {
                LOGGER.info("Error: unknown version in file {}: {}", filename, version);
                return null;
            }

            // Verify that the block is within the reported range
            if (height < startHeight || height > endHeight) {
                LOGGER.info("Error: requested height {} but the range of file {} is {}-{}",
                        height, filename, startHeight, endHeight);
                return null;
            }

            // Seek to the location of the block index in the variable length header
            final int locationOfBlockIndexInVariableHeaderSegment = (height - startHeight) * INT_LENGTH;
            file.seek(fixedHeaderLength + locationOfBlockIndexInVariableHeaderSegment);

            // Read the value to obtain the index of this block in the data segment
            int locationOfBlockInDataSegment = file.readInt();

            // Now seek to the block data itself
            int dataSegmentStartIndex = fixedHeaderLength + variableHeaderLength + INT_LENGTH; // Confirmed correct
            file.seek(dataSegmentStartIndex + locationOfBlockInDataSegment);

            // Read the block metadata
            int blockHeight = file.readInt(); // Do not remove or comment out, as it is moving the file pointer
            int blockLength = file.readInt(); // Do not remove or comment out, as it is moving the file pointer

            // Ensure the block height matches the one requested
            if (blockHeight != height) {
                LOGGER.info("Error: height {} does not match requested: {}", blockHeight, height);
                return null;
            }

            // Now retrieve the block's serialized bytes
            byte[] blockBytes = new byte[blockLength];
            file.read(blockBytes);

            return blockBytes;

        } catch (FileNotFoundException e) {
            LOGGER.info("File {} not found: {}", filename, e.getMessage());
            return null;
        } catch (IOException e) {
            LOGGER.info("Unable to read block {} from archive: {}", height, e.getMessage());
            return null;
        }
        finally {
            // Close the file
            if (file != null) {
                try {
                    file.close();
                } catch (IOException e) {
                    // Failed to close, but no need to handle this
                }
            }
        }
    }

    public void invalidateFileListCache() {
        this.fileListCache = null;
    }

}
