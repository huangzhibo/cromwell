package cromwell.database.sql

import java.sql.Timestamp

import cromwell.database.sql.tables.WorkflowStoreEntry

import scala.concurrent.{ExecutionContext, Future}

trait WorkflowStoreSqlDatabase {
  this: SqlDatabase =>
  /*
  The following section relates to:
____    __    ____  ______   .______       __  ___  _______  __        ______   ____    __    ____
\   \  /  \  /   / /  __  \  |   _  \     |  |/  / |   ____||  |      /  __  \  \   \  /  \  /   /
 \   \/    \/   / |  |  |  | |  |_)  |    |  '  /  |  |__   |  |     |  |  |  |  \   \/    \/   /
  \            /  |  |  |  | |      /     |    <   |   __|  |  |     |  |  |  |   \            /
   \    /\    /   |  `--'  | |  |\  \----.|  .  \  |  |     |  `----.|  `--'  |    \    /\    /
    \__/  \__/     \______/  | _| `._____||__|\__\ |__|     |_______| \______/      \__/  \__/

     _______.___________.  ______   .______       _______
    /       |           | /  __  \  |   _  \     |   ____|
   |   (----`---|  |----`|  |  |  | |  |_)  |    |  |__
    \   \       |  |     |  |  |  | |      /     |   __|
.----)   |      |  |     |  `--'  | |  |\  \----.|  |____
|_______/       |__|      \______/  | _| `._____||_______|

   */

  /**
    * Set all running workflows to aborting state.
    */
  def setStateToState(fromWorkflowState: String, toWorkflowState: String)
                     (implicit ec: ExecutionContext): Future[Unit]

  /**
    * Set the workflow to aborting state.
    *
    * @return Number of rows deleted, plus Some(restarted) if the workflow exists in the store, where restarted is true
    *         if the workflow's heartbeat timestamp was cleared on restart, and None if the workflow does not exist.
    */
  def deleteOrUpdateWorkflowToState(workflowExecutionUuid: String,
                                    workflowStateToDelete: String,
                                    workflowStateForUpdate: String)
                                   (implicit ec: ExecutionContext): Future[(Int, Option[Boolean])]

  /**
    * Adds the requested WorkflowSourceFiles to the store.
    */
  def addWorkflowStoreEntries(workflowStoreEntries: Iterable[WorkflowStoreEntry])
                             (implicit ec: ExecutionContext): Future[Unit]

  /**
    * Retrieves up to limit workflows which have not already been pulled into the engine and updates their state.
    * NOTE: Rows are returned with the query state, NOT the update state.
    */
  def fetchWorkflowsInState(limit: Int,
                            cromwellId: String,
                            heartbeatTimestampStart: Timestamp,
                            heartbeatTimestampTo: Timestamp,
                            workflowStateFrom: String,
                            workflowStateTo: String,
                            workflowStateExcluded: String)
                           (implicit ec: ExecutionContext): Future[Seq[WorkflowStoreEntry]]

  def writeWorkflowHeartbeats(workflowExecutionUuids: Set[String],
                              heartbeatTimestampOption: Option[Timestamp])
                             (implicit ec: ExecutionContext): Future[Int]

  /**
    * Clears out cromwellId and heartbeatTimestamp for all workflow store entries currently assigned
    * the specified cromwellId.
    */
  def releaseWorkflowStoreEntries(cromwellId: String)(implicit ec: ExecutionContext): Future[Unit]

  /**
    * Deletes a workflow from the database, returning the number of rows affected.
    */
  def removeWorkflowStoreEntry(workflowExecutionUuid: String)(implicit ec: ExecutionContext): Future[Int]

  def workflowStateCounts(implicit ec: ExecutionContext): Future[Map[String, Int]]

  /**
    * Returns the number of rows updated from on-hold to submitted.
    */
  def setOnHoldToSubmitted(workflowExecutionUuid: String, fromWorkflowState: String, toWorkflowState: String)
                          (implicit ec: ExecutionContext): Future[Int]
}
