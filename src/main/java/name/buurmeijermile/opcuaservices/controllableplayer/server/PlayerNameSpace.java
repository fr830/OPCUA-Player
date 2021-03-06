/* 
 * The MIT License
 *
 * Copyright 2018 Milé Buurmeijer <mbuurmei at netscape.net>.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package name.buurmeijermile.opcuaservices.controllableplayer.server;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.logging.Level;

import com.google.common.collect.Lists;
import java.util.EnumSet;
import java.util.Set;

import name.buurmeijermile.opcuaservices.controllableplayer.measurements.Asset;
import name.buurmeijermile.opcuaservices.controllableplayer.measurements.DataBackendController;
import name.buurmeijermile.opcuaservices.controllableplayer.measurements.MeasurementPoint;
import name.buurmeijermile.opcuaservices.controllableplayer.measurements.PointInTime;
import name.buurmeijermile.opcuaservices.controllableplayer.measurements.PointInTime.BASE_UNIT_OF_MEASURE;

import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.AccessContext;
import org.eclipse.milo.opcua.sdk.server.api.DataItem;
import org.eclipse.milo.opcua.sdk.server.api.MethodInvocationHandler;
import org.eclipse.milo.opcua.sdk.server.api.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.api.Namespace;
import org.eclipse.milo.opcua.sdk.server.model.nodes.variables.AnalogItemNode;
import org.eclipse.milo.opcua.sdk.server.model.nodes.variables.DataItemNode;
import org.eclipse.milo.opcua.sdk.server.model.nodes.variables.TwoStateDiscreteNode;
import org.eclipse.milo.opcua.sdk.server.nodes.AttributeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.ServerNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaMethodNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.util.AnnotationBasedInvocationHandler;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.EUInformation;
import org.eclipse.milo.opcua.stack.core.types.structured.Range;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.eclipse.milo.opcua.stack.core.types.structured.WriteValue;
import org.eclipse.milo.opcua.stack.core.util.FutureUtils;
import org.eclipse.milo.opcua.sdk.core.ValueRanks;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;


public class PlayerNameSpace implements Namespace {
    // class variables
    public static final String NAMESPACE_URI = "urn:SmileSoft:OPC_UA_Player";
    private static final String OBJECTFOLDER = "Assets";
    // instance variables
    private final SubscriptionModel subscriptionModel;
    private final OpcUaServer server;
    private final UShort namespaceIndex;
    private final DataBackendController dataBackEndController;
    private final RestrictedAccessDelegate restrictedDelegateAccess;
    private List<UaVariableNode> variableNodes = null;
    private List<Asset> assets = null;

    /**
     * The intended namespace for the OPC UA Player server.
     * @param server the OPC UA server
     * @param namespaceIndex  the index of this namespace
     * @param aDataBackendController the back end controller that exposes its measurement points in this namespace
     */
    public PlayerNameSpace(OpcUaServer server, UShort namespaceIndex, DataBackendController aDataBackendController) {
        // store parameters
        this.server = server;
        this.namespaceIndex = namespaceIndex;
        this.variableNodes = new ArrayList<>();
        // create subscription model for this server
        this.subscriptionModel = new SubscriptionModel(server, this);
        // create basic restriction access delegate to be used for all created nodes in this namespace
        this.restrictedDelegateAccess = new RestrictedAccessDelegate(identity -> {
            switch ( identity.toString()) {
                case "admin": {
                    return AccessLevel.READ_WRITE;
                }
                case "user": {
                    return AccessLevel.READ_ONLY;
                }
                default: {
                    return AccessLevel.NONE;
                }
            }
        });
        // set data backend for retreiving measurements
        this.dataBackEndController = aDataBackendController;
        // get the assets from back end controller
        this.assets = this.dataBackEndController.getAssets();
        // create node list in this namespace based on the available assets in the backend controlller
        this.createUANodeList( this.assets);
        // add the remote control OPC UA method to this servernamespace so that the OPC UA player can be remotely controlled by OPC UA clients
        this.addRemoteControlMethodNode();
    }
    
    /**
     * Creates the UA node list for this namespace based on the assets from the backend
     * @param assets the assets with its measurement points
     */
    private void createUANodeList( List<Asset> assets) {
        // create a "PMP" folder under Root/Objects and add it to the node manager
        NodeId folderNodeId = new NodeId(this.namespaceIndex, OBJECTFOLDER);
        UaFolderNode folderNode = new UaFolderNode(
                this.server.getNodeMap(),
                folderNodeId,
                new QualifiedName(this.namespaceIndex, OBJECTFOLDER),
                LocalizedText.english(OBJECTFOLDER)
        );
        // add to the server node map
        this.server.getNodeMap().addNode(folderNode);
        // and into the folder structure
        try {
            // make sure this new folder shows up under the server's Objects folder
            this.server.getUaNamespace().addReference(
                    Identifiers.ObjectsFolder,
                    Identifiers.Organizes,
                    true,
                    folderNodeId.expanded(),
                    NodeClass.Object
            );
        } catch (UaException ex) {
            Logger.getLogger(PlayerNameSpace.class.getName()).log(Level.SEVERE, "Adding reference to folder failed", ex);
        }
        // add all assets and their measurement points under this folder
        for ( Asset anAsset: assets) {
            // first create folder node for the asset with this node id
            String nodeId = "Assets/" + anAsset.getName() + "/";
            UaFolderNode assetFolder = new UaFolderNode(
                    this.server.getNodeMap(),
                    new NodeId(this.namespaceIndex, nodeId),
                    new QualifiedName(this.namespaceIndex, anAsset.getName()),
                    LocalizedText.english( anAsset.getName())
            );
            // add folder node "Wissel_*" under the PMP node
            this.server.getNodeMap().addNode(assetFolder);
            folderNode.addOrganizes(assetFolder);
            // then add all the measurement points to this asset folder node
            for (MeasurementPoint aMeasurementPoint : anAsset.getMeasurementPoints()) {
                // for each measurement point create a variable node
                // set main info for the variable node
                String name = aMeasurementPoint.getName();
                String measurementPointID = Integer.toString( aMeasurementPoint.getID());
                NodeId typeId = this.getNodeType( aMeasurementPoint);
                Set<AccessLevel> accessLevels = this.getAccessLevel( aMeasurementPoint.getAccessRight());
                // create variable node based on this info [several steps]
                DataItemNode dataItemNode = null;
                // [step 1] retrieve the unit of measure from the measurement point
                BASE_UNIT_OF_MEASURE aBaseUnitOfMeasure = aMeasurementPoint.getTheBaseUnitOfMeasure();
                // [step 2] check if it has a unit of measure and do all [steps *a] when nu UoM or else all [steps *b]
                if (aBaseUnitOfMeasure != BASE_UNIT_OF_MEASURE.NoUoM) {
                    // [step 3a] it has a UoM => create OPC UA analog item node
                    AnalogItemNode analogItemNode = 
                            new AnalogItemNode( 
                                    this.server.getNodeMap(), 
                                    new NodeId(this.namespaceIndex, measurementPointID), 
                                    new QualifiedName(this.namespaceIndex, name),
                                    LocalizedText.english(name),
                                    LocalizedText.english("an analog variable node"),
                                    UInteger.MIN,
                                    UInteger.MIN,
                                    null,
                                    typeId,
                                    ValueRanks.Scalar,
                                    null,
                                    Unsigned.ubyte( AccessLevel.getMask( accessLevels)),
                                    Unsigned.ubyte( AccessLevel.getMask( accessLevels)), 
                                    0.0,
                                    false
                            );
                    // [step 4a] create UoM information object
                    EUInformation euInformation = 
                            new EUInformation(
                                    this.getNamespaceUri(), 
                                    0, 
                                    LocalizedText.english(
                                            aMeasurementPoint.getTheBaseUnitOfMeasure().toString().subSequence(0, 1).toString()), 
                                    LocalizedText.english(
                                            aMeasurementPoint.getTheBaseUnitOfMeasure().toString())
                            );
                    // [step 5a] set UoM to node
                    analogItemNode.setEngineeringUnits( euInformation);
                    // set measurement range
                    analogItemNode.setEURange( new Range( 0.0, 20.0));
                    dataItemNode = analogItemNode;
                } else {
                    // [step 3b] it has no UoM => create two state discreteItemNode
                    TwoStateDiscreteNode twoStateDiscreteItemNode = 
                            new TwoStateDiscreteNode( 
                                    this.server.getNodeMap(), // server node map
                                    new NodeId(this.namespaceIndex, measurementPointID), // nodeId
                                    new QualifiedName(this.namespaceIndex, name), // browse name
                                    LocalizedText.english(name), // display name
                                    LocalizedText.english("a boolean variable node"), // description
                                    UInteger.MIN, // write mask
                                    UInteger.MIN, // user write mask
                                    null, // data value
                                    typeId, // data type nodeId
                                    ValueRanks.Scalar, // value rank
                                    null, // array dimensions
                                    Unsigned.ubyte(AccessLevel.getMask(AccessLevel.CurrentRead)), // access level
                                    Unsigned.ubyte(AccessLevel.getMask(AccessLevel.CurrentRead)), // user access level
                                    0.0, // minimum sampling interval
                                    false // no historizing
                            ); //
                    dataItemNode = twoStateDiscreteItemNode;
                }
                // create reference to this OPC UA varable node in the measurement point, 
                // so that the node value can be updated when the measurement points value changes
                // also is the initial value set properly
                aMeasurementPoint.setUaVariableNode( dataItemNode);
                // set the restricted access delegate of this node
                dataItemNode.setAttributeDelegate( this.restrictedDelegateAccess);
                // add to proper OPC UA structures
                this.server.getNodeMap().addNode( dataItemNode);
                // add reference back and forth between the current folder and this containing variable node
                assetFolder.addOrganizes( dataItemNode);
                // add this variable node to the list of variable node so it can be queried by the data backend
                this.variableNodes.add( dataItemNode);
            }
        }
    }

    private NodeId getNodeType( MeasurementPoint aMeasurementPoint) {
        if (aMeasurementPoint.getTheBaseUnitOfMeasure() == BASE_UNIT_OF_MEASURE.NoUoM) {
            return Identifiers.Boolean;
        } else {
            return Identifiers.Double;
        }
    }

    private void addRemoteControlMethodNode() {
        try {
            // create a "PlayerControl" folder and add it to the node manager
            NodeId folderNodeId = new NodeId(namespaceIndex, "PlayerControl");
            UaFolderNode folderNode = new UaFolderNode(
                this.server.getNodeMap(),
                folderNodeId,
                new QualifiedName( this.namespaceIndex, "PlayerControl"),
                LocalizedText.english("PlayerControl")
            );
            // add this method node to servers node map
            this.server.getNodeMap().addNode(folderNode);
            // make sure this new method folder shows up under the server's Objects folder
            this. server.getUaNamespace().addReference(
                Identifiers.ObjectsFolder,
                Identifiers.Organizes,
                true,
                folderNodeId.expanded(),
                NodeClass.Object
            );
            // bulld the method node
            UaMethodNode methodNode = UaMethodNode.builder(server.getNodeMap())
                .setNodeId(new NodeId( this.namespaceIndex, "Player/remote-control(x)"))
                .setBrowseName(new QualifiedName( this.namespaceIndex, "remote-control(x)"))
                .setDisplayName(new LocalizedText(null, "remote-control(x)"))
                .setDescription(
                    LocalizedText.english("Remote controle for this player: input '1' => Play, '5' => Pause, '6' => Stop, '7' => Endlessly loop input file"))
                .build();
            // add an invocation handler point towards the control method and the actual class that can be 'controlled'
            AnnotationBasedInvocationHandler invocationHandler =
                AnnotationBasedInvocationHandler.fromAnnotatedObject(
                    this.server.getNodeMap(), new RemoteControlMethod( this.dataBackEndController));
            // set the method input and output properties and the created invocation handler
            methodNode.setProperty(UaMethodNode.InputArguments, invocationHandler.getInputArguments());
            methodNode.setProperty(UaMethodNode.OutputArguments, invocationHandler.getOutputArguments());
            methodNode.setInvocationHandler(invocationHandler);
            // set the access restriction delegate
            methodNode.setAttributeDelegate( this.restrictedDelegateAccess);
            // add the method node to the namespace
            this.server.getNodeMap().addNode(methodNode);
            // and add a reference to the created folder node refering to the method node
            folderNode.addReference(new Reference(
                folderNode.getNodeId(),
                Identifiers.HasComponent,
                methodNode.getNodeId().expanded(),
                methodNode.getNodeClass(),
                true
            ));
            // and add a reference back from the method node to the folder node
            methodNode.addReference(new Reference(
                methodNode.getNodeId(),
                Identifiers.HasComponent,
                folderNode.getNodeId().expanded(),
                folderNode.getNodeClass(),
                false
            ));
            // add in same folder a varaiable node that shows the current state
            String nodeName = "RunState";
            // create variable node
            UaVariableNode variableNode = new UaVariableNode.UaVariableNodeBuilder(server.getNodeMap())
                .setNodeId(new NodeId(namespaceIndex, "Player/" + nodeName))
                .setAccessLevel(ubyte(AccessLevel.getMask(AccessLevel.READ_WRITE)))
                .setUserAccessLevel(ubyte(AccessLevel.getMask(AccessLevel.READ_WRITE)))
                .setBrowseName(new QualifiedName(namespaceIndex, nodeName))
                .setDisplayName(LocalizedText.english(nodeName))
                .setDataType(Identifiers.String)
                .setTypeDefinition(Identifiers.BaseDataVariableType)
                .setValueRank(ValueRanks.Scalar)
                .build();
            // make this varable node known to data backen end controller so that it can updates to the runstate into this node
            this.dataBackEndController.setRunStateUANode( variableNode);
            // add node to server map
            server.getNodeMap().addNode(variableNode);
            // add node to this player folder
            folderNode.addOrganizes(variableNode);
        } catch (UaException uaex) {
            Logger.getLogger(PlayerNameSpace.class.getName()).log(Level.SEVERE, "Error adding nodes: " + uaex.getMessage(), uaex);
        } catch (Exception ex) {
            Logger.getLogger(PlayerNameSpace.class.getName()).log(Level.SEVERE, "Error creating invocation handler: " + ex.getMessage(), ex);
        }
    }

    public void activateSimulation() {
        // get the node we want to simulate the value of
        UaVariableNode aNode = this.variableNodes.get(1);
        // create thread to alter the value of the simulated variable node over and over
        Thread simulator = new Thread() {
            // the internal state that will change all the time
            boolean state = false;
            @Override
            public void run() {
                while (true) {
                    try {
                        // flip state
                        state = !state;
                        // set the selected node value
                        aNode.setValue( new DataValue(new Variant( state)));
                        Logger.getLogger(PlayerNameSpace.class.getName()).log(Level.INFO, "Simulated value set to " + state);
                        // wait for one second
                        Thread.sleep( 1000);
                    } catch (InterruptedException ex) {
                        Logger.getLogger(PlayerNameSpace.class.getName()).log(Level.SEVERE, "Interrupted in activate simulation", ex);
                    }
                }
            }
        };
        // start this thread
        simulator.start();
    }

    @Override
    public UShort getNamespaceIndex() {
        return this.namespaceIndex;
    }

    @Override
    public String getNamespaceUri() {
        return NAMESPACE_URI;
    }

    @Override
    public CompletableFuture<List<Reference>> browse(AccessContext context, NodeId nodeId) {
        ServerNode node = this.server.getNodeMap().get(nodeId);

        if (node != null) {
            return CompletableFuture.completedFuture(node.getReferences());
        } else {
            return FutureUtils.failedFuture(new UaException(StatusCodes.Bad_NodeIdUnknown));
        }
    }

    @Override
    public void read( ReadContext context, Double maxAge, TimestampsToReturn timestamps,  List<ReadValueId> readValueIds) {

        List<DataValue> results = Lists.newArrayListWithCapacity(readValueIds.size());

        for (ReadValueId readValueId : readValueIds) {
            ServerNode node = this.server.getNodeMap().get(readValueId.getNodeId());

            if (node != null) {
                DataValue value = node.readAttribute(
                    new AttributeContext(context),
                    readValueId.getAttributeId(),
                    timestamps,
                    readValueId.getIndexRange(),
                    readValueId.getDataEncoding()
                );

                results.add(value);
            } else {
                results.add(new DataValue(StatusCodes.Bad_NodeIdUnknown));
            }
        }

        context.complete(results);
    }

    @Override
    public void write(WriteContext context, List<WriteValue> writeValues) {
        List<StatusCode> results = Lists.newArrayListWithCapacity(writeValues.size());

        for (WriteValue writeValue : writeValues) {
            ServerNode node = this.server.getNodeMap().get(writeValue.getNodeId());

            if (node != null) {
                try {
                    node.writeAttribute(
                        new AttributeContext(context),
                        writeValue.getAttributeId(),
                        writeValue.getValue(),
                        writeValue.getIndexRange()
                    );

                    results.add(StatusCode.GOOD);
                    String logOutput = "Wrote value " + writeValue.getValue().getValue() + " to " + AttributeId.from(writeValue.getAttributeId()).map(Object::toString).orElse("unknown") + " attribute of " + node.getNodeId();
                    Logger.getLogger(DataBackendController.class.getName()).log(Level.INFO, logOutput);
                } catch (UaException uaex) {
                    Logger.getLogger(DataBackendController.class.getName()).log(Level.SEVERE, "Unable to write value=" + writeValue.getValue(), uaex);
                    results.add( uaex.getStatusCode());
                }
            } else {
                results.add(new StatusCode(StatusCodes.Bad_NodeIdUnknown));
            }
        }
        context.complete(results);
    }

    @Override
    public void onDataItemsCreated(List<DataItem> dataItems) {
        this.subscriptionModel.onDataItemsCreated(dataItems);
    }

    @Override
    public void onDataItemsModified(List<DataItem> dataItems) {
        this.subscriptionModel.onDataItemsModified(dataItems);
    }

    @Override
    public void onDataItemsDeleted(List<DataItem> dataItems) {
        this.subscriptionModel.onDataItemsDeleted(dataItems);
    }

    @Override
    public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {
        this.subscriptionModel.onMonitoringModeChanged(monitoredItems);
    }

    @Override
    public Optional<MethodInvocationHandler> getInvocationHandler(NodeId methodId) {
        Optional<ServerNode> node = server.getNodeMap().getNode(methodId);

        return node.flatMap(n -> {
            if (n instanceof UaMethodNode) {
                return ((UaMethodNode) n).getInvocationHandler();
            } else {
                return Optional.empty();
            }
        });
    }

    private Set<AccessLevel> getAccessLevel(PointInTime.ACCESS_RIGHT accessRight) {
        Set<AccessLevel> resultSet = EnumSet.noneOf(AccessLevel.class);
        switch ( accessRight) {
            case Read: { 
                resultSet.add(AccessLevel.CurrentRead);
                break; 
            }
            case Write: {
                resultSet.add(AccessLevel.CurrentWrite);
                break; 
            }
            case Both: {
                resultSet.add(AccessLevel.CurrentRead);
                resultSet.add(AccessLevel.CurrentWrite);
                break; 
            }
            default: {
                Logger.getLogger(PlayerNameSpace.class.getName()).log(Level.SEVERE, "No valid accessright passed to getAccessLevel");
                break;
            }
        }
        return resultSet;
    }
}
