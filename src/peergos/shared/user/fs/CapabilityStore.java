package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.SafeRandom;
import peergos.shared.user.EntryPoint;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import java.util.stream.*;

/** This class implements the mechanism by which users share Capabilities with each other
 *
 * Each unidirectional sharing relationship has a sharing folder /source_user/sharing/recipient_user/
 * In this sharing directory is an append only list of capabilities which the source user has granted to the recipient
 * user. This is implemented as a series of numbered files in the directory with a maximum number of capabilities per
 * file. Knowing the index of the capability in the overall list you can calcualte the file name, and the offset in the
 * file at which the capability is stored. Write and read capabilities form logically separate append only lists.
 *
 * To avoid reparsing the entire capability list at every login, the capabilities and their retrieved paths are stored
 * in a cache for each source user located at /recipient_user/.capabilitycache/source_user
 * Each of these cache files is just a serialized CapabilitiesFromUser
 */
public class CapabilityStore {
    private static final String CAPABILITY_CACHE_DIR = ".capabilitycache";
    private static final String READ_SHARING_FILE_NAME = "sharing.r";
    private static final String EDIT_SHARING_FILE_NAME = "sharing.w";

    public static CompletableFuture<FileWrapper> addReadOnlySharingLinkTo(FileWrapper sharedDir,
                                                                          AbsoluteCapability capability,
                                                                          NetworkAccess network,
                                                                          Crypto crypto) {
        return addSharingLinkTo(sharedDir, capability.readOnly(), network, crypto, CapabilityStore.READ_SHARING_FILE_NAME);
    }

    public static CompletableFuture<FileWrapper> addEditSharingLinkTo(FileWrapper sharedDir,
                                                                      WritableAbsoluteCapability capability,
                                                                      NetworkAccess network,
                                                                      Crypto crypto) {
        return addSharingLinkTo(sharedDir, capability, network, crypto, CapabilityStore.EDIT_SHARING_FILE_NAME);
    }

    private static CompletableFuture<FileWrapper> addSharingLinkTo(FileWrapper sharedDir,
                                                                  AbsoluteCapability capability,
                                                                  NetworkAccess network,
                                                                  Crypto crypto,
                                                                  String capStoreFilename) {
        if (! sharedDir.isDirectory() || ! sharedDir.isWritable()) {
            CompletableFuture<FileWrapper> error = new CompletableFuture<>();
            error.completeExceptionally(new IllegalArgumentException("Can only add link to a writable directory!"));
            return error;
        }

        return sharedDir.getChild(capStoreFilename, network)
                .thenCompose(capStore -> {
                    byte[] serializedCapability = capability.toCbor().toByteArray();
                    AsyncReader.ArrayBacked newCapability = new AsyncReader.ArrayBacked(serializedCapability);
                    long startIndex = capStore.map(f -> f.getSize()).orElse(0L);
                    return sharedDir.uploadFileSection(capStoreFilename, newCapability, false,
                            startIndex, startIndex + serializedCapability.length, Optional.empty(), true,
                            network, crypto, x -> {}, sharedDir.generateChildLocations(1, crypto.random));
                });
    }

    /**
     *
     * @param homeDirSupplier
     * @param friendSharedDir
     * @param friendName
     * @param network
     * @param crypto
     * @param saveCache
     * @return a pair of the current capability index, and the valid capabilities
     */
    public static CompletableFuture<CapabilitiesFromUser> loadReadAccessSharingLinks(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                     FileWrapper friendSharedDir,
                                                                                     String friendName,
                                                                                     NetworkAccess network,
                                                                                     Crypto crypto,
                                                                                     boolean saveCache) {
        return loadSharingLinks( homeDirSupplier, friendSharedDir, friendName, network, crypto,
                saveCache, READ_SHARING_FILE_NAME);
    }

    /**
     *
     * @param homeDirSupplier
     * @param friendSharedDir
     * @param friendName
     * @param network
     * @param crypto
     * @param saveCache
     * @return a pair of the current capability index, and the valid capabilities
     */
    public static CompletableFuture<CapabilitiesFromUser> loadWriteAccessSharingLinks(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                      FileWrapper friendSharedDir,
                                                                                      String friendName,
                                                                                      NetworkAccess network,
                                                                                      Crypto crypto,
                                                                                      boolean saveCache) {

        return loadSharingLinks( homeDirSupplier, friendSharedDir, friendName, network, crypto,
                saveCache, EDIT_SHARING_FILE_NAME);
    }

    private static CompletableFuture<CapabilitiesFromUser> loadSharingLinks(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                            FileWrapper friendSharedDir,
                                                                            String friendName,
                                                                            NetworkAccess network,
                                                                            Crypto crypto,
                                                                            boolean saveCache,
                                                                            String capStoreFilename) {
        return friendSharedDir.getChild(capStoreFilename, network)
                .thenCompose(capFile -> {
                    if (! capFile.isPresent())
                        return CompletableFuture.completedFuture(new CapabilitiesFromUser(0, Collections.emptyList()));
                    long capFilesize = capFile.get().getSize();
                    return getSharingCacheFile(friendName, homeDirSupplier, network, crypto, capStoreFilename).thenCompose(optCachedFile -> {
                        if(! optCachedFile.isPresent()) {
                            return readSharingFile(friendSharedDir.getName(), friendSharedDir.owner(), capFile.get(), network, crypto.random)
                                    .thenCompose(res -> {
                                        if(saveCache && res.size() > 0) {
                                            return saveRetrievedCapabilityCache(capFilesize, homeDirSupplier, friendName,
                                                    network, crypto, res, capStoreFilename);
                                        } else {
                                            return CompletableFuture.completedFuture(new CapabilitiesFromUser(capFilesize, res));
                                        }
                                    });
                        } else {
                            FileWrapper cachedFile = optCachedFile.get();
                            return readRetrievedCapabilityCache(cachedFile, network, crypto.random).thenCompose(cache -> {
                                if (capFilesize == cache.getBytesRead())
                                    return CompletableFuture.completedFuture(cache);
                                return readSharingFile(cache.getBytesRead(), friendSharedDir.getName(),
                                        friendSharedDir.owner(), capFile.get(), network, crypto.random)
                                        .thenCompose(res -> {
                                            if (saveCache) {
                                                return saveRetrievedCapabilityCache(capFilesize, homeDirSupplier, friendName,
                                                        network, crypto, res, capStoreFilename);
                                            } else {
                                                return CompletableFuture.completedFuture(new CapabilitiesFromUser(capFilesize, res));
                                            }
                                        });
                            });
                        }
                    });
                });
    }


    public static CompletableFuture<CapabilitiesFromUser> loadReadAccessSharingLinksFromIndex(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                              FileWrapper friendSharedDir,
                                                                                              String friendName,
                                                                                              NetworkAccess network,
                                                                                              Crypto crypto,
                                                                                              long startOffset,
                                                                                              boolean saveCache) {

        return loadSharingLinksFromIndex(homeDirSupplier, friendSharedDir, friendName, network, crypto,
                startOffset, saveCache, READ_SHARING_FILE_NAME);
    }

    public static CompletableFuture<CapabilitiesFromUser> loadWriteAccessSharingLinksFromIndex(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                               FileWrapper friendSharedDir,
                                                                                               String friendName,
                                                                                               NetworkAccess network,
                                                                                               Crypto crypto,
                                                                                               long startOffset,
                                                                                               boolean saveCache) {

        return loadSharingLinksFromIndex(homeDirSupplier, friendSharedDir, friendName, network, crypto,
                startOffset, saveCache, EDIT_SHARING_FILE_NAME);
    }

    private static CompletableFuture<CapabilitiesFromUser> loadSharingLinksFromIndex(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                     FileWrapper friendSharedDir,
                                                                                     String friendName,
                                                                                     NetworkAccess network,
                                                                                     Crypto crypto,
                                                                                     long startOffset,
                                                                                     boolean saveCache,
                                                                                     String capFilename) {
        return friendSharedDir.getChild(capFilename, network)
                .thenCompose(file -> {
                    if (! file.isPresent())
                        return CompletableFuture.completedFuture(new CapabilitiesFromUser(0, Collections.emptyList()));
                    long capFileSize = file.get().getSize();
                    return readSharingFile(startOffset, friendSharedDir.getName(), friendSharedDir.owner(), file.get(), network, crypto.random)
                            .thenCompose(res -> {
                                if (saveCache) {
                                    return saveRetrievedCapabilityCache(capFileSize - startOffset, homeDirSupplier, friendName,
                                            network, crypto, res, capFilename);
                                } else {
                                    return CompletableFuture.completedFuture(new CapabilitiesFromUser(capFileSize - startOffset, res));
                                }
                            });
                });
    }

    public static CompletableFuture<Long> getReadOnlyCapabilityFileSize(FileWrapper friendSharedDir,
                                                                        NetworkAccess network) {
        return getCapabilityFileSize(READ_SHARING_FILE_NAME, friendSharedDir, network);
    }

    public static CompletableFuture<Long> getEditableCapabilityFileSize(FileWrapper friendSharedDir,
                                                                        NetworkAccess network) {
        return getCapabilityFileSize(EDIT_SHARING_FILE_NAME, friendSharedDir, network);
    }

    private static CompletableFuture<Long> getCapabilityFileSize(String filename, FileWrapper friendSharedDir,
                                                                 NetworkAccess network) {
        return friendSharedDir.getChild(filename, network)
                .thenApply(capFile -> capFile.map(f -> f.getFileProperties().size).orElse(0L));
    }

    public static CompletableFuture<List<CapabilityWithPath>> readSharingFile(String ownerName,
                                                                              PublicKeyHash owner,
                                                                              FileWrapper file,
                                                                              NetworkAccess network,
                                                                              SafeRandom random) {
        return readSharingFile(0, ownerName, owner, file, network, random);
    }

    public static CompletableFuture<List<CapabilityWithPath>> readSharingFile(long startOffset,
                                                                              String ownerName,
                                                                              PublicKeyHash owner,
                                                                              FileWrapper file,
                                                                              NetworkAccess network,
                                                                              SafeRandom random) {
        return file.getInputStream(network, random, x -> {})
                .thenCompose(reader -> reader.seek(startOffset))
                .thenCompose(seeked -> readSharingRecords(ownerName, owner, seeked, file.getSize() - startOffset, network));
    }

    private static CompletableFuture<List<CapabilityWithPath>> readSharingRecords(String ownerName,
                                                                                  PublicKeyHash owner,
                                                                                  AsyncReader reader,
                                                                                  long maxBytesToRead,
                                                                                  NetworkAccess network) {
        if (maxBytesToRead == 0)
            return CompletableFuture.completedFuture(Collections.emptyList());

        List<AbsoluteCapability> caps = new ArrayList<>();
        return reader.parseStream(AbsoluteCapability::fromCbor, caps::add, maxBytesToRead)
                .thenCompose(bytesRead -> {
                    return Futures.combineAllInOrder(caps.stream().map(pointer -> {
                        EntryPoint entry = new EntryPoint(pointer, ownerName);
                        return network.retrieveEntryPoint(entry).thenCompose(fileOpt -> {
                            if (fileOpt.isPresent()) {
                                try {
                                    CompletableFuture<List<CapabilityWithPath>> res = fileOpt.get().getPath(network)
                                            .thenApply(path -> Collections.singletonList(new CapabilityWithPath(path, pointer)));
                                    return res;
                                } catch (NoSuchElementException nsee) {
                                    return Futures.errored(nsee); //a file ancestor no longer exists!?
                                }
                            } else {
                                return CompletableFuture.completedFuture(Collections.<CapabilityWithPath>emptyList());
                            }
                        }).exceptionally(t -> Collections.<CapabilityWithPath>emptyList());
                    }).collect(Collectors.toList()))
                            .thenApply(res -> res.stream().flatMap(x -> x.stream()).collect(Collectors.toList()))
                            .thenCompose(results -> readSharingRecords(ownerName, owner, reader,
                                    maxBytesToRead - bytesRead, network)
                                    .thenApply(recurse -> Stream.concat(results.stream(), recurse.stream())
                                            .collect(Collectors.toList())));
                });
    }

    private static CompletableFuture<Optional<FileWrapper>> getSharingCacheFile(String friendName,
                                                                                Supplier<CompletableFuture<FileWrapper>> getHome,
                                                                                NetworkAccess network,
                                                                                Crypto crypto,
                                                                                String capabilityType) {
        return getCapabilityCacheDir(getHome, network, crypto)
                .thenCompose(cacheDir -> cacheDir.getChild(friendName + capabilityType, network));
    }

    private static CompletableFuture<FileWrapper> getCapabilityCacheDir(Supplier<CompletableFuture<FileWrapper>> getHome,
                                                                        NetworkAccess network,
                                                                        Crypto crypto) {
        return getHome.get()
                .thenCompose(home -> home.getChild(CAPABILITY_CACHE_DIR, network)
                        .thenCompose(opt ->
                                opt.map(CompletableFuture::completedFuture)
                                        .orElseGet(() -> home.mkdir(CAPABILITY_CACHE_DIR, network, true, crypto)
                                                .thenCompose(x -> getCapabilityCacheDir(getHome, network, crypto)))));
    }

    public static CompletableFuture<CapabilitiesFromUser> saveRetrievedCapabilityCache(long recordsRead,
                                                                                       Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                       String friendName,
                                                                                       NetworkAccess network,
                                                                                       Crypto crypto,
                                                                                       List<CapabilityWithPath> retrievedCapabilities,
                                                                                       String capabilityType) {
        CapabilitiesFromUser capabilitiesFromUser = new CapabilitiesFromUser(recordsRead, retrievedCapabilities);
        byte[] data = capabilitiesFromUser.serialize();
        AsyncReader.ArrayBacked dataReader = new AsyncReader.ArrayBacked(data);
        return getCapabilityCacheDir(homeDirSupplier, network, crypto)
                .thenCompose(cacheDir -> cacheDir.uploadOrOverwriteFile(friendName + capabilityType, dataReader,
                        (long) data.length, network, crypto, x-> {},
                        cacheDir.generateChildLocationsFromSize(data.length, crypto.random))
                        .thenApply(x -> capabilitiesFromUser));
    }

    private static CompletableFuture<CapabilitiesFromUser> readRetrievedCapabilityCache(FileWrapper cacheFile,
                                                                                        NetworkAccess network,
                                                                                        SafeRandom random) {
        return cacheFile.getInputStream(network, random, x -> { })
                .thenCompose(reader -> {
                    byte[] storeData = new byte[(int) cacheFile.getSize()];
                    return reader.readIntoArray(storeData, 0, storeData.length)
                            .thenApply(x -> CapabilitiesFromUser.fromCbor(CborObject.fromByteArray(storeData)));
                });
    }
}
