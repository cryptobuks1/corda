package net.corda.explorer.views

import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView
import javafx.beans.binding.Bindings
import javafx.collections.ObservableList
import javafx.geometry.HPos
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Parent
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TabPane
import javafx.scene.control.TableView
import javafx.scene.control.TitledPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox
import javafx.scene.text.FontWeight
import javafx.scene.text.TextAlignment
import net.corda.client.jfx.model.StateMachineData
import net.corda.client.jfx.model.StateMachineDataModel
import net.corda.client.jfx.model.StateMachineStatus
import net.corda.client.jfx.model.observableList
import net.corda.client.jfx.model.observableListReadOnly
import net.corda.client.jfx.utils.map
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.toBase58String
import net.corda.core.flows.FlowInitiator
import net.corda.core.transactions.SignedTransaction
import net.corda.explorer.formatters.FlowInitiatorFormatter
import net.corda.explorer.formatters.FlowNameFormatter
import net.corda.explorer.identicon.identicon
import net.corda.explorer.identicon.identiconToolTip
import net.corda.explorer.model.CordaView
import net.corda.explorer.model.CordaWidget
import net.corda.explorer.ui.setCustomCellFactory
import tornadofx.*

// TODO Rethink whole idea of showing communication as table, it should be tree view for each StateMachine (with subflows and other communication)
class StateMachineViewer : CordaView("Flow Triage") {
    override val root by fxml<TabPane>()
    override val icon = FontAwesomeIcon.HEARTBEAT
    override val widgets = listOf(CordaWidget(title, StateMachineViewer.StateMachineWidget())).observable()
    private val progressViewTable by fxid<TableView<StateMachineData>>()
    private val doneViewTable by fxid<TableView<StateMachineData>>()
    private val errorViewTable by fxid<TableView<StateMachineData>>()

    private class StateMachineWidget : BorderPane() {
        private val flows by observableListReadOnly(StateMachineDataModel::stateMachinesInProgress)

        // TODO can add stats: in progress, errored, maybe done to the widget?
        init {
            right {
                label {
                    textProperty().bind(Bindings.size(flows).map(Number::toString))
                    BorderPane.setAlignment(this, Pos.BOTTOM_RIGHT)
                }
            }
        }
    }

    private val stateMachinesInProgress by observableList(StateMachineDataModel::stateMachinesInProgress)
    private val stateMachinesFinished by observableList(StateMachineDataModel::stateMachinesFinished)
    private val stateMachinesError by observableList(StateMachineDataModel::stateMachinesError)

    fun makeColumns(table: TableView<StateMachineData>, tableItems: ObservableList<StateMachineData>, withResult: Boolean = true) {
        table.apply {
            items = tableItems
            if (withResult) {
                rowExpander(expandOnDoubleClick = true) {
                    add(StateMachineDetailsView(it).root)
                }.apply {
                    // Column stays the same size, but we don't violate column restricted resize policy for the whole table view.
                    minWidth = 26.0
                    maxWidth = 26.0
                }
            }
            column("ID", StateMachineData::id) { // TODO kill that ID column
                minWidth = 100.0
                maxWidth = 200.0
            }.setCustomCellFactory {
                label("$it") {
                    val hash = SecureHash.sha256(it.toString())
                    graphic = identicon(hash, 15.0)
                    tooltip = identiconToolTip(hash) //TODO Have id instead of hash.
                }
            }
            column("Flow name", StateMachineData::stateMachineName).cellFormat { text = FlowNameFormatter.boring.format(it) }
            column("Initiator", StateMachineData::flowInitiator).cellFormat { text = FlowInitiatorFormatter.format(it) }
            column("Flow Status", StateMachineData::stateMachineStatus).cellFormat {
                if (it == null)
                    text = "No progress data"
                else text = it.status
            } // TODO null
            column("Result", StateMachineData::addRmStatus).setCustomCellFactory {
                if (it is StateMachineStatus.Removed) {
                    if (it.result.error == null) {
                        label("Success") {
                            graphic = FontAwesomeIconView(FontAwesomeIcon.CHECK).apply {
                                glyphSize = 15.0
                                textAlignment = TextAlignment.CENTER
                                style = "-fx-fill: green"
                            }
                        }
                    } else {
                        label("Error") {
                            graphic = FontAwesomeIconView(FontAwesomeIcon.BOLT).apply {
                                glyphSize = 15.0
                                textAlignment = TextAlignment.CENTER
                                style = "-fx-fill: -color-4"
                            }
                        }
                    }
                }
                else {
                    label("In progress") {
                        // TODO Other icons: spnner, hourglass-half, hourglass-1, send-o, space-shuttle ;)
                        graphic = FontAwesomeIconView(FontAwesomeIcon.ROCKET).apply {
                            glyphSize = 15.0
                            textAlignment = TextAlignment.CENTER
                            style = "-fx-fill: lightslategrey"
                        }
                    }
                }
            }
        }
    }

    init {
        makeColumns(progressViewTable, stateMachinesInProgress, false)
        makeColumns(doneViewTable, stateMachinesFinished)
        makeColumns(errorViewTable, stateMachinesError)
    }

    private inner class StateMachineDetailsView(val smmData: StateMachineData) : Fragment() {
        override val root by fxml<Parent>()
        private val flowNameLabel by fxid<Label>()
        private val flowProgressPane by fxid<TitledPane>()
        private val flowInitiatorGrid by fxid<GridPane>()
        private val flowResultVBox by fxid<VBox>()

        init {
            flowNameLabel.apply {
                text = FlowNameFormatter.boring.format(smmData.stateMachineName)
            }
            flowProgressPane.apply {
                content = label {
                    text = smmData.stateMachineStatus.value?.status // TODO later we can do some magic with showing progress steps with subflows
                }
            }
            when (smmData.flowInitiator) {
                is FlowInitiator.Shell -> makeShellGrid(flowInitiatorGrid) // TODO Extend this when we will have more information on shell user.
                is FlowInitiator.Peer -> makePeerGrid(flowInitiatorGrid, smmData.flowInitiator as FlowInitiator.Peer)
                is FlowInitiator.RPC -> makeRPCGrid(flowInitiatorGrid, smmData.flowInitiator as FlowInitiator.RPC)
                is FlowInitiator.Scheduled -> makeScheduledGrid(flowInitiatorGrid, smmData.flowInitiator as FlowInitiator.Scheduled)
            }
            val status = smmData.addRmStatus.value
            if (status is StateMachineStatus.Removed) {
                status.result.match(onValue =  { makeResultVBox(flowResultVBox, it) }, onError = { makeErrorVBox(flowResultVBox, it) })
            }
        }
    }

    private fun <T>makeResultVBox(vbox: VBox, result: T) {
        if (result == null) {
            vbox.apply { label("No return value from flow.") }
        } else if (result is SignedTransaction) {
//                scrollpane {
//                    hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
//                    vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            // TODO Make link to transaction view
            vbox.apply {
                label("Signed transaction")
                label {
                    text = result.id.toString()
                    graphic = identicon(result.id, 30.0)
                    tooltip = identiconToolTip(result.id)
                }
//                }
            }
        } else if (result is Unit) {
            vbox.apply { label("Flow completed with success.") }
        }
        else {
            // TODO Here we could have sth different than SignedTransaction
            vbox.apply { label(result.toString()) }
        }
    }

    private fun makeErrorVBox(vbox: VBox, error: Throwable) {
        vbox.apply {
            label("Error") {
                graphic = FontAwesomeIconView(FontAwesomeIcon.BOLT).apply {
                    glyphSize = 30
                    textAlignment = TextAlignment.CENTER
                    style = "-fx-fill: -color-4"
                }
            }
        }
        // TODO border styling?
        vbox.apply {
            vbox {
                spacing = 10.0
                label { text = error::class.simpleName }
                scrollpane { //TODO do that error scroll pane nicely
                    hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
                    vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                    label { text = error.message }
                }
            }
        }
    }

    // TODO test
    private fun makeShellGrid(gridPane: GridPane) {
        val title = gridPane.lookup("#flowInitiatorTitle") as Label
        title.apply {
            text = "Flow started by shell user"
        }
    }

    private fun makePeerGrid(gridPane: GridPane, initiator: FlowInitiator.Peer) {
        val title = gridPane.lookup("#flowInitiatorTitle") as Label
        title.apply {
            text = "Flow started by a peer node"
        }
        gridPane.apply{
            //                scrollpane { // TODO scrollbar vbox + hbox
//                    hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
//                    vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            row {
                label("Legal name: ") {
                    gridpaneConstraints { hAlignment = HPos.LEFT }
                    style { fontWeight = FontWeight.BOLD }
                    minWidth = 150.0
                    prefWidth = 150.0
                }
                label(initiator.party.name) { gridpaneConstraints { hAlignment = HPos.LEFT } }
            }
            row {
                label("Owning key: ") {
                    gridpaneConstraints { hAlignment = HPos.LEFT }
                    style { fontWeight = FontWeight.BOLD }
                    minWidth = 150.0
                    prefWidth = 150.0
                }
                label(initiator.party.owningKey.toBase58String()) { gridpaneConstraints { hAlignment = HPos.LEFT } }
            }
        }
    }

    private fun makeRPCGrid(gridPane: GridPane, initiator: FlowInitiator.RPC) {
        val title = gridPane.lookup("#flowInitiatorTitle") as Label
        title.apply {
            text = "Flow started by a RPC user"
        }
        gridPane.apply {
            row {
                label("User name: ") {
                    gridpaneConstraints { hAlignment = HPos.LEFT }
                    style { fontWeight = FontWeight.BOLD }
                    prefWidth = 150.0
                }
                label(initiator.username) { gridpaneConstraints { hAlignment = HPos.LEFT } }
            }
        }
    }

    // TODO test
    private fun makeScheduledGrid(gridPane: GridPane, initiator: FlowInitiator.Scheduled) {
        val title = gridPane.lookup("flowInitiatorTitle") as Label
        title.apply {
            text = "Flow started as scheduled activity"
        }
        gridPane.apply {
            row {
                label("Scheduled state: ") {
                    gridpaneConstraints { hAlignment = HPos.LEFT }
                    style { fontWeight = FontWeight.BOLD }
                    prefWidth = 150.0
                }
                label(initiator.scheduledState.ref.toString()) { gridpaneConstraints { hAlignment = HPos.LEFT } } //TODO format
            }
            row {
                label("Scheduled at: ") {
                    gridpaneConstraints { hAlignment = HPos.LEFT }
                    style { fontWeight = FontWeight.BOLD }
                    prefWidth = 150.0
                }
                label(initiator.scheduledState.scheduledAt.toString()) { gridpaneConstraints { hAlignment = HPos.LEFT } } //TODO format
            }
        }
    }
}
