/*
 * This file is part of Bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.dao.state.monitoring;

import bisq.core.dao.DaoSetupService;
import bisq.core.dao.state.DaoStateListener;
import bisq.core.dao.state.DaoStateService;
import bisq.core.dao.state.GenesisTxInfo;
import bisq.core.dao.state.model.blockchain.Block;
import bisq.core.dao.state.monitoring.messages.GetDaoStateHashRequest;
import bisq.core.dao.state.monitoring.messages.NewDaoStateHashMessage;
import bisq.core.dao.state.monitoring.network.DaoStateNetworkService;

import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.network.Connection;

import bisq.common.UserThread;
import bisq.common.crypto.Hash;

import javax.inject.Inject;

import org.apache.commons.lang3.ArrayUtils;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Monitors the DaoState with using a hash fo the complete daoState and make it accessible to the network for
 * so we can detect quickly if any consensus issue arise. The data does not contain any private user
 * data so sharing it on demand has no privacy concerns.
 *
 * We request the state from the connected seed nodes after batch processing of BSQ is complete as well as we start
 * to listen for broadcast messages from our peers about dao state of new blocks. It could be that the received dao
 * state from the peers is already covering the next block we have not received yet. So we only take data in account
 * which are inside the block height we have already. To avoid such race conditions we delay the broadcasting of our
 * state to the peers to not get ignored it in case they have not received the block yet.
 *
 * We do not persist that chain of hashes and we only create it from the blocks we parse, so we start from the height
 * of the latest block in the snapshot.
 *
 * TODO maybe request full state?
 * TODO add p2p network data for monitoring
 * TODO auto recovery
 */
@Slf4j
public class DaoStateMonitoringService implements DaoSetupService, DaoStateListener, DaoStateNetworkService.Listener {
    public interface Listener {
        void onDaoStateBlockchainChanged();
    }

    private final DaoStateService daoStateService;
    private final DaoStateNetworkService daoStateNetworkService;
    private final GenesisTxInfo genesisTxInfo;

    @Getter
    private final LinkedList<DaoStateBlock> daoStateBlockchain = new LinkedList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private boolean parseBlockChainComplete;
    @Getter
    private boolean isInConflict;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public DaoStateMonitoringService(DaoStateService daoStateService,
                                     DaoStateNetworkService daoStateNetworkService,
                                     GenesisTxInfo genesisTxInfo) {
        this.daoStateService = daoStateService;
        this.daoStateNetworkService = daoStateNetworkService;
        this.genesisTxInfo = genesisTxInfo;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DaoSetupService
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void addListeners() {
        this.daoStateService.addDaoStateListener(this);
        daoStateNetworkService.addListener(this);
    }

    @Override
    public void start() {
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DaoStateListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onParseBlockChainComplete() {
        parseBlockChainComplete = true;
        daoStateNetworkService.addListeners();

        // We wait for processing messages until we have completed batch processing
        int fromBlockHeight = daoStateService.getChainHeight() - 10;
        daoStateNetworkService.requestHashesFromAllConnectedSeedNodes(fromBlockHeight);
    }

    @Override
    public void onDaoStateChanged(Block block) {
        processNewBlock(block);
    }

    @Override
    public void onSnapshotApplied() {
        // We could got a reset from a reorg, so we clear all and start over from the genesis block.
        daoStateBlockchain.clear();
        daoStateNetworkService.reset();

        daoStateService.getBlocks().forEach(this::processNewBlock);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DaoStateNetworkService.Listener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onNewDaoStateHashMessage(NewDaoStateHashMessage newDaoStateHashMessage, Connection connection) {
        if (newDaoStateHashMessage.getDaoStateHash().getBlockHeight() <= daoStateService.getChainHeight()) {
            processPeersDaoStateHash(newDaoStateHashMessage.getDaoStateHash(), connection.getPeersNodeAddressOptional());
        }
    }

    @Override
    public void onGetDaoStateHashRequest(Connection connection, GetDaoStateHashRequest getDaoStateHashRequest) {
        int fromBlockHeight = getDaoStateHashRequest.getFromBlockHeight();
        List<DaoStateHash> daoStateHashes = daoStateBlockchain.stream()
                .filter(e -> e.getBlockHeight() >= fromBlockHeight)
                .map(DaoStateBlock::getMyDaoStateHash)
                .collect(Collectors.toList());
        daoStateNetworkService.sendGetDaoStateHashResponse(connection, getDaoStateHashRequest.getNonce(), daoStateHashes);
    }

    @Override
    public void onPeersDaoStateHash(DaoStateHash daoStateHash, Optional<NodeAddress> peersNodeAddress) {
        processPeersDaoStateHash(daoStateHash, peersNodeAddress);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void requestHashesFromGenesisBlockHeight(String peersAddress) {
        daoStateNetworkService.requestHashes(genesisTxInfo.getGenesisBlockHeight(), peersAddress);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Listeners
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void processNewBlock(Block block) {
        //TODO handle reorgs TODO need to start from gen

        byte[] prevHash;
        int height = block.getHeight();
        if (daoStateBlockchain.isEmpty()) {
            // Only at genesis we allow an empty prevHash
            if (height == genesisTxInfo.getGenesisBlockHeight()) {
                prevHash = new byte[0];
            } else {
                log.warn("DaoStateBlockchain is empty but we received the block which was not the genesis block. " +
                        "We stop execution here.");
                return;
            }
        } else {
            // TODO check if in reorg cases it might be a valid case
            checkArgument(height > daoStateBlockchain.getLast().getBlockHeight(),
                    "We got a block the same blockHeight as our previous block in the daoStateBlockchain.");
            prevHash = daoStateBlockchain.getLast().getHash();
        }
        byte[] stateHash = daoStateService.getDaoStateHash();
        // We include the prev. hash in our new hash so we can be sure that if one hash is matching all the past would
        // match as well.
        byte[] combined = ArrayUtils.addAll(prevHash, stateHash);
        byte[] hash = Hash.getRipemd160hash(combined);

        DaoStateHash myDaoStateHash = new DaoStateHash(height, hash, prevHash);
        DaoStateBlock daoStateBlock = new DaoStateBlock(myDaoStateHash);
        daoStateBlockchain.add(daoStateBlock);
        listeners.forEach(Listener::onDaoStateBlockchainChanged);

        log.info("Add daoStateBlock at processNewBlock:\n{}", daoStateBlock);

        // We only broadcast after parsing of blockchain is complete
        if (parseBlockChainComplete) {
            // We delay broadcast to give peers enough time to have received the block.
            // Otherwise they would ignore our data if received block is in future to their local blockchain.
            int delayInSec = 1 + new Random().nextInt(5);
            UserThread.runAfter(() -> daoStateNetworkService.broadcastMyDaoStateHash(myDaoStateHash), delayInSec);
        }
    }

    private void processPeersDaoStateHash(DaoStateHash daoStateHash, Optional<NodeAddress> peersNodeAddress) {
        AtomicBoolean changed = new AtomicBoolean(false);
        AtomicBoolean isInConflict = new AtomicBoolean(this.isInConflict);
        daoStateBlockchain.stream()
                .filter(e -> e.getBlockHeight() == daoStateHash.getBlockHeight()).findAny()
                .ifPresent(daoStateBlock -> {
                    String peersNodeAddressAsString = peersNodeAddress.map(NodeAddress::getFullAddress)
                            .orElseGet(() -> "Unknown peer " + new Random().nextInt(10000));
                    daoStateBlock.putInPeersMap(peersNodeAddressAsString, daoStateHash);
                    if (!daoStateBlock.getMyDaoStateHash().hasEqualHash(daoStateHash)) {
                        daoStateBlock.putInConflictMap(peersNodeAddressAsString, daoStateHash);
                        isInConflict.set(true);
                    }
                    changed.set(true);
                });

        this.isInConflict = isInConflict.get();

        if (changed.get()) {
            listeners.forEach(Listener::onDaoStateBlockchainChanged);
        }
    }
}
