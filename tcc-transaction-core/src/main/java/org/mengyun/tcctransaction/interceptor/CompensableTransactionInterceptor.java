package org.mengyun.tcctransaction.interceptor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mengyun.tcctransaction.api.ParticipantStatus;
import org.mengyun.tcctransaction.api.TransactionStatus;
import org.mengyun.tcctransaction.exception.IllegalTransactionStatusException;
import org.mengyun.tcctransaction.exception.NoExistedTransactionException;
import org.mengyun.tcctransaction.transaction.Transaction;
import org.mengyun.tcctransaction.transaction.TransactionManager;
import org.mengyun.tcctransaction.utils.ReflectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Created by changmingxie on 10/30/15.
 */
public class CompensableTransactionInterceptor {

    static final Logger logger = LoggerFactory.getLogger(CompensableTransactionInterceptor.class.getSimpleName());

    private TransactionManager transactionManager;

    private ObjectMapper jackson = new ObjectMapper();

    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public Object interceptCompensableMethod(TransactionMethodJoinPoint pjp) throws Throwable {

        Transaction transaction = transactionManager.getCurrentTransaction();
        CompensableMethodContext compensableMethodContext = new CompensableMethodContext(pjp, transaction);

        // if method is @Compensable and no transaction context and no transaction, then root
        // else if method is @Compensable and has transaction context and no transaction ,then provider
        switch (compensableMethodContext.getParticipantRole()) {
            case ROOT:
                return rootMethodProceed(compensableMethodContext);
            case PROVIDER:
                return providerMethodProceed(compensableMethodContext);
            default:
                return compensableMethodContext.proceed();
        }
    }

    private Object rootMethodProceed(CompensableMethodContext compensableMethodContext) throws Throwable {

        Object returnValue = null;

        Transaction transaction = null;

        boolean asyncConfirm = compensableMethodContext.getAnnotation().asyncConfirm();

        boolean asyncCancel = compensableMethodContext.getAnnotation().asyncCancel();

        try {

            transaction = transactionManager.begin(compensableMethodContext.getUniqueIdentity());

            try {
                returnValue = compensableMethodContext.proceed();
            } catch (Throwable tryingException) {

                transactionManager.rollback(asyncCancel);

                throw tryingException;
            }

            transactionManager.commit(asyncConfirm);

        } finally {
            transactionManager.cleanAfterCompletion(transaction);
        }

        return returnValue;
    }

    private Object providerMethodProceed(CompensableMethodContext compensableMethodContext) throws Throwable {

        Transaction transaction = null;

        boolean asyncConfirm = compensableMethodContext.getAnnotation().asyncConfirm();

        boolean asyncCancel = compensableMethodContext.getAnnotation().asyncCancel();

        try {

            switch (TransactionStatus.valueOf(compensableMethodContext.getTransactionContext().getStatus())) {
                case TRYING:
                    transaction = transactionManager.propagationNewBegin(compensableMethodContext.getTransactionContext());

                    Object result = null;

                    try {
                        result = compensableMethodContext.proceed();
                        transactionManager.changeStatus(TransactionStatus.TRY_SUCCESS);
                    } catch (Throwable e) {
                        transactionManager.changeStatus(TransactionStatus.TRY_FAILED);
                        throw e;
                    }

                    return result;

                case CONFIRMING:
                    try {
                        transaction = transactionManager.propagationExistBegin(compensableMethodContext.getTransactionContext());
                        transactionManager.commit(asyncConfirm);
                    } catch (NoExistedTransactionException excepton) {
                        //the transaction has been commit,ignore it.
                        try {
                            logger.warn("no existed transaction found at CONFIRMING stage, will ignore and confirm automatically. transaction:" + jackson.writeValueAsString(transaction));
                        } catch (JsonProcessingException e) {
                            logger.error("failed to serialize transaction {}", e);
                        }
                    }
                    break;
                case CANCELLING:

                    try {

                        //The transaction' status of this branch transaction, passed from consumer side.
                        int transactionStatusFromConsumer = compensableMethodContext.getTransactionContext().getParticipantStatus();

                        transaction = transactionManager.propagationExistBegin(compensableMethodContext.getTransactionContext());

                        // Only if transaction's status is at TRY_SUCCESS、TRY_FAILED、CANCELLING stage we can call rollback.
                        // If transactionStatusFromConsumer is TRY_SUCCESS, no mate current transaction is TRYING or not, also can rollback.
                        // transaction's status is TRYING while transactionStatusFromConsumer is TRY_SUCCESS may happen when transaction's changeStatus is async.
                        if (transaction.getStatus().equals(TransactionStatus.TRY_SUCCESS)
                                || transaction.getStatus().equals(TransactionStatus.TRY_FAILED)
                                || transaction.getStatus().equals(TransactionStatus.CANCELLING)
                                || transactionStatusFromConsumer == ParticipantStatus.TRY_SUCCESS.getId()) {
                            transactionManager.rollback(asyncCancel);
                        } else {
                            //in this case, transaction's Status is TRYING and transactionStatusFromConsumer is TRY_FAILED
                            // this may happen if timeout exception throws during rpc call.
                            throw new IllegalTransactionStatusException("Branch transaction status is TRYING, cannot rollback directly, waiting for recovery job to rollback.");
                        }

                    } catch (NoExistedTransactionException exception) {
                        //the transaction has been rollback,ignore it.
                        try {
                            logger.info("no existed transaction found at CANCELLING stage, will ignore and cancel automatically. transaction:" + jackson.writeValueAsString(transaction));
                        } catch (JsonProcessingException e) {
                            logger.error("failed to serialize transaction {}", e);
                        }
                    }
                    break;
            }

        } finally {
            transactionManager.cleanAfterCompletion(transaction);
        }

        Method method = compensableMethodContext.getMethod();

        return ReflectionUtils.getNullValue(method.getReturnType());
    }
}
