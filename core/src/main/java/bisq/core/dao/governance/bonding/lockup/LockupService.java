/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.dao.governance.bonding.lockup;

import bisq.core.btc.exceptions.TransactionVerificationException;
import bisq.core.btc.exceptions.TxBroadcastException;
import bisq.core.btc.exceptions.TxMalleabilityException;
import bisq.core.btc.exceptions.WalletException;
import bisq.core.btc.wallet.BsqWalletService;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.TxBroadcaster;
import bisq.core.btc.wallet.WalletsManager;
import bisq.core.dao.governance.bond.BondedRolesService;
import bisq.core.dao.governance.bonding.BondingConsensus;
import bisq.core.dao.governance.bonding.bond.BondWithHash;
import bisq.core.dao.state.model.governance.Role;

import bisq.common.handlers.ExceptionHandler;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;

import javax.inject.Inject;

import java.io.IOException;

import java.util.function.Consumer;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class LockupService {
    private final WalletsManager walletsManager;
    private final BsqWalletService bsqWalletService;
    private final BtcWalletService btcWalletService;
    private final BondedRolesService bondedRolesService;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public LockupService(WalletsManager walletsManager,
                         BsqWalletService bsqWalletService,
                         BtcWalletService btcWalletService,
                         BondedRolesService bondedRolesService) {
        this.walletsManager = walletsManager;
        this.bsqWalletService = bsqWalletService;
        this.btcWalletService = btcWalletService;
        this.bondedRolesService = bondedRolesService;
    }

    public void publishLockupTx(Coin lockupAmount, int lockTime, LockupType lockupType, BondWithHash bondWithHash,
                                Consumer<String> resultHandler, ExceptionHandler exceptionHandler) {
        checkArgument(lockTime <= BondingConsensus.getMaxLockTime() &&
                lockTime >= BondingConsensus.getMinLockTime(), "lockTime not in rage");
        if (bondWithHash instanceof Role) {
            Role role = (Role) bondWithHash;
            if (bondedRolesService.wasRoleAlreadyBonded(role)) {
                exceptionHandler.handleException(new RuntimeException("The role has been used already for a lockup tx."));
                return;
            }
        }

        byte[] hash = BondingConsensus.getHash(bondWithHash);
        try {
            byte[] opReturnData = BondingConsensus.getLockupOpReturnData(lockTime, lockupType, hash);
            final Transaction lockupTx = createLockupTx(lockupAmount, opReturnData);

            //noinspection Duplicates
            walletsManager.publishAndCommitBsqTx(lockupTx, new TxBroadcaster.Callback() {
                @Override
                public void onSuccess(Transaction transaction) {
                    resultHandler.accept(transaction.getHashAsString());
                }

                @Override
                public void onTxMalleability(TxMalleabilityException exception) {
                    exceptionHandler.handleException(exception);
                }

                @Override
                public void onFailure(TxBroadcastException exception) {
                    exceptionHandler.handleException(exception);
                }
            });

        } catch (TransactionVerificationException | InsufficientMoneyException | WalletException |
                IOException exception) {
            exceptionHandler.handleException(exception);
        }
    }

    private Transaction createLockupTx(Coin lockupAmount, byte[] opReturnData)
            throws InsufficientMoneyException, WalletException, TransactionVerificationException {
        Transaction preparedTx = bsqWalletService.getPreparedLockupTx(lockupAmount);
        Transaction txWithBtcFee = btcWalletService.completePreparedBsqTx(preparedTx, true, opReturnData);
        return bsqWalletService.signTx(txWithBtcFee);
    }
}
