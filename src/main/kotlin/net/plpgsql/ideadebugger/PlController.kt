/*
 * Copyright (c) 2022. Alexandre Boyer
 */

package net.plpgsql.ideadebugger

import com.intellij.database.connection.throwable.info.WarningInfo
import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionPoint
import com.intellij.database.datagrid.DataAuditors
import com.intellij.database.datagrid.DataConsumer
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.debugger.SqlDebugController
import com.intellij.database.util.SearchPath
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.ui.content.Content
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import java.util.regex.Pattern
import kotlin.properties.Delegates

class PlController(
    val facade: PlFacade,
    val project: Project,
    val connectionPoint: DatabaseConnectionPoint,
    val ownerEx: DataRequest.OwnerEx,
    val virtualFile: VirtualFile?,
    val rangeMarker: RangeMarker?,
    val searchPath: SearchPath?,
    val callExpression: SqlFunctionCallExpression?,
) : SqlDebugController() {

    private val logger = getLogger<PlController>()
    private val pattern = Pattern.compile(".*PLDBGBREAK:([0-9]+).*")
    private lateinit var plProcess: PlProcess
    lateinit var xSession: XDebugSession
    private val queryAuditor = QueryAuditor()
    private val queryConsumer = QueryConsumer()
    private val windowLister = ToolListener()

    val executor = PlExecutor(this)

    private var busConnection = project.messageBus.connect()

    var entryPoint by Delegates.notNull<Long>()

    override fun getReady() {
        logger.debug("getReady")
        executor.checkExtension()
        if (!checkExecutor()) {
            return
        }
        if (callExpression != null) {
            val callDef = parseFunctionCall(callExpression)
            assert(callDef.first.isNotEmpty() && callDef.first.size <= 2) { "Error while parsing ${callExpression.text}" }
            executor.searchCallee(callDef.first, callDef.second)
        } else {
            executor.setError("Invalid call expression")
        }
        checkExecutor()
    }

    override fun initLocal(session: XDebugSession): XDebugProcess {
        busConnection.subscribe(ToolWindowManagerListener.TOPIC, windowLister)
        xSession = session
        plProcess = PlProcess(this)
        return plProcess
    }

    override fun initRemote(connection: DatabaseConnection) {
        logger.info("initRemote")
        executor.startDebug(connection)
        if (checkExecutor()) {
            ownerEx.messageBus.addAuditor(queryAuditor)
            ownerEx.messageBus.addConsumer(queryConsumer)
        }
    }

    override fun debugBegin() {
        logger.info("debugBegin")
    }

    fun checkExecutor(): Boolean {
        if (executor.hasError()) {
            facade.disableMe = searchPath
            runInEdt {
                Messages.showMessageDialog(
                    project,
                    executor.lastMessage.content,
                    "PL/pg Debugger",
                    if (executor.hasError()) Messages.getErrorIcon() else Messages.getWarningIcon()
                )
            }
            xSession.stop()
            return false;
        }
        return true
    }

    override fun debugEnd() {
        logger.info("debugEnd")
    }

    override fun close() {
        logger.info("close")
        windowLister.close()
        busConnection.disconnect()
        /*
        dbgConnection.runCatching {
            dbgConnection.remoteConnection.close()
        }
         */
        vfsCleanup()
    }

    private fun vfsCleanup() {
        /*val con = createDebugConnection(project, connectionPoint)
        val toRemove = plGetShadowList(con, PlVFS.getInstance().all().map { it.oid })
        runReadAction {
            val vfs = PlVFS.getInstance()
            toRemove.forEach { vfs.remove(it) }
            vfs.all().forEach { it.unload() }
        }
        //con.remoteConnection.close()

         */
    }

    inner class QueryAuditor : DataAuditors.Adapter() {
        override fun warn(context: DataRequest.Context, info: WarningInfo) {
            if (context.request.owner == ownerEx) {
                val matcher = pattern.matcher(info.message)
                if (matcher.matches()) {
                    val port = matcher.group(1).toInt()
                    executor.attachToPort(port)
                    if (checkExecutor()) {
                        plProcess.startDebug()
                    }
                }
            }
        }

        override fun fetchStarted(context: DataRequest.Context, index: Int) {
            if (context.request.owner == ownerEx) {
                close()
            }
        }
    }

    inner class QueryConsumer : DataConsumer.Adapter() {
        override fun afterLastRowAdded(context: DataRequest.Context, total: Int) {
            if (context.request.owner == ownerEx) {
                println("HI")
            }
        }

    }

    inner class ToolListener : ToolWindowManagerListener {
        private var window: ToolWindow? = null
        private var first: Boolean = false
        private var acutal: Content? = null
        override fun toolWindowShown(toolWindow: ToolWindow) {
            if (toolWindow.id == "Debug") {
                window = toolWindow
                first = true
            }
            if (first && toolWindow.id != "Debug") {
                window?.show()
                acutal = window?.contentManager?.contents?.find {
                    it.tabName == xSession.sessionName
                }
                first = false
            }
        }

        fun close() {
            runInEdt {
                windowLister.acutal?.let { window?.contentManager?.removeContent(it, true) }
            }
        }
    }
}


