/*
 * Copyright 2017 JBoss by Red Hat.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.as.server.deploymentoverlay;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEPLOYMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEPLOYMENT_OVERLAY;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ENABLED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REDEPLOY;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REDEPLOY_AFFECTED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REDEPLOY_LINKS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RUNTIME_NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STEPS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBDEPLOYMENT;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.controller.operations.DomainOperationTransformer;
import org.jboss.as.controller.operations.OperationAttachments;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.server.logging.ServerLogger;
import org.jboss.dmr.ModelNode;

/**
 * Utility class for finding and updating deployments affected by an overlay change. This is where the magic of getting
 * deployments affected by an overlay is happening.
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class AffectedDeploymentOverlay {

    private AffectedDeploymentOverlay() {
    }

    /**
     * Returns all the deployment runtime names associated with an overlay accross all server groups.
     *
     * @param context the current OperationContext.
     * @param overlay the name of the overlay.
     * @return all the deployment runtime names associated with an overlay accross all server groups.
     */
    public static Set<String> listAllLinks(OperationContext context, String overlay) {
        Set<String> serverGoupNames = listServerGroupsReferencingOverlay(context.readResourceFromRoot(PathAddress.EMPTY_ADDRESS), overlay);
        Set<String> links = new HashSet<>();
        for (String serverGoupName : serverGoupNames) {
            links.addAll(listLinks(context, PathAddress.pathAddress(
                    PathElement.pathElement(SERVER_GROUP, serverGoupName),
                    PathElement.pathElement(DEPLOYMENT_OVERLAY, overlay))));
        }
        return links;
    }

    /**
     * Returns all the deployment runtime names associated with an overlay.
     *
     * @param context the current OperationContext.
     * @param overlayAddress the address for the averlay.
     * @return all the deployment runtime names associated with an overlay.
     */
    public static Set<String> listLinks(OperationContext context, PathAddress overlayAddress) {
        Resource overlayResource = context.readResourceFromRoot(overlayAddress);
        if (overlayResource.hasChildren(DEPLOYMENT)) {
            return overlayResource.getChildrenNames(DEPLOYMENT);
        }
        return Collections.emptySet();
    }

    /**
     * We are adding a redeploy operation step for each specified deployment runtime name.
     *
     * @param context
     * @param deploymentsRootAddress
     * @param runtimeNames
     * @throws OperationFailedException
     */
    public static void redeployLinks(OperationContext context, PathAddress deploymentsRootAddress, Set<String> runtimeNames) throws OperationFailedException {
        Set<String> deploymentNames = listDeployments(context.readResourceFromRoot(deploymentsRootAddress), runtimeNames);
        for (String deploymentName : deploymentNames) {
            PathAddress address = deploymentsRootAddress.append(DEPLOYMENT, deploymentName);
            OperationStepHandler handler = context.getRootResourceRegistration().getOperationHandler(address, REDEPLOY);
            ModelNode operation = addRedeployStep(address);
            ServerLogger.AS_ROOT_LOGGER.debugf("Redeploying %s at address %s with handler %s", deploymentName, address, handler);
            assert handler != null;
            assert operation.isDefined();
            context.addStep(operation, handler, OperationContext.Stage.MODEL);
        }
    }

    /**
     * We are adding a redeploy operation step for each specified deployment runtime name.
     *
     * @param context
     * @param deploymentsRootAddress
     * @param deploymentNames
     * @throws OperationFailedException
     */
    public static void redeployDeployments(OperationContext context, PathAddress deploymentsRootAddress, Set<String> deploymentNames) throws OperationFailedException {
        for (String deploymentName : deploymentNames) {
            PathAddress address = deploymentsRootAddress.append(DEPLOYMENT, deploymentName);
            OperationStepHandler handler = context.getRootResourceRegistration().getOperationHandler(address, REDEPLOY);
            ModelNode operation = addRedeployStep(address);
            ServerLogger.AS_ROOT_LOGGER.debugf("Redeploying %s at address %s with handler %s", deploymentName, address, handler);
            assert handler != null;
            assert operation.isDefined();
            context.addStep(operation, handler, OperationContext.Stage.MODEL);
        }
    }

    /**
     * It will look for all the deployments (in every server-group) with a runtimeName in the specified list of runtime
     * names and then transform the operation so that every server in those server groups will redeploy the affected
     * deployments.
     *
     * @param removeOperation
     * @see #transformOperation
     * @param context
     * @param runtimeNames
     * @throws OperationFailedException
     */
    public static void redeployLinksAndTransformOperationForDomain(OperationContext context, Set<String> runtimeNames, ModelNode removeOperation) throws OperationFailedException {
        Set<String> serverGroupNames = listServerGroupsReferencingOverlay(context.readResourceFromRoot(PathAddress.EMPTY_ADDRESS), context.getCurrentAddressValue());
        Map<String, Set<String>> deploymentPerServerGroup = new HashMap<>();
        for (String serverGoupName : serverGroupNames) {
            deploymentPerServerGroup.put(serverGoupName,
                    listDeployments(context.readResourceFromRoot(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, serverGoupName))),runtimeNames));
        }
        if (deploymentPerServerGroup.isEmpty()) {
            runtimeNames.forEach(s -> ServerLogger.ROOT_LOGGER.debugf("We haven't found any server-group for %s", s));
            return;
        }
        Operations.CompositeOperationBuilder opBuilder = Operations.CompositeOperationBuilder.create();
        if(removeOperation != null) {
             opBuilder.addStep(removeOperation);
        }
        //Add a deploy step for each affected deployment in its server-group.
        deploymentPerServerGroup.entrySet().stream().filter((entry) -> (!entry.getValue().isEmpty())).forEach((entry) -> {
            entry.getValue().forEach((deploymentName) -> opBuilder.addStep(addRedeployStep(context.getCurrentAddress().getParent().append(SERVER_GROUP, entry.getKey()).append(DEPLOYMENT, deploymentName))));
        });
        // Add the domain op transformer
        List<DomainOperationTransformer> transformers = context.getAttachment(OperationAttachments.SLAVE_SERVER_OPERATION_TRANSFORMERS);
        if (transformers == null) {
            context.attach(OperationAttachments.SLAVE_SERVER_OPERATION_TRANSFORMERS, transformers = new ArrayList<>());
        }
        final ModelNode slave = opBuilder.build().getOperation();
        transformers.add(new OverlayOperationTransformer(slave, context.getCurrentAddress()));
    }

    /**
     * It will look for all the deployments under the deploymentsRootAddress with a runtimeName in the specified list of
     * runtime names and then transform the operation so that every server having those deployments will redeploy the
     * affected deployments.
     *
     * @see #transformOperation
     * @param removeOperation
     * @param context
     * @param deploymentsRootAddress
     * @param runtimeNames
     * @throws OperationFailedException
     */
     public static void redeployLinksAndTransformOperation(OperationContext context, ModelNode removeOperation, PathAddress deploymentsRootAddress, Set<String> runtimeNames) throws OperationFailedException {
        Set<String> deploymentNames = listDeployments(context.readResourceFromRoot(deploymentsRootAddress), runtimeNames);
        if (deploymentNames.isEmpty()) {
            runtimeNames.forEach(s -> ServerLogger.ROOT_LOGGER.debugf("We haven't found any deployment for %s in server-group %s", s, deploymentsRootAddress.getLastElement().getValue()));
            return;
        }
        Operations.CompositeOperationBuilder opBuilder = Operations.CompositeOperationBuilder.create();
        if(removeOperation != null) {
             opBuilder.addStep(removeOperation);
        }
        for (String deploymentName : deploymentNames) {
            opBuilder.addStep(addRedeployStep(deploymentsRootAddress.append(DEPLOYMENT, deploymentName)));
        }
        List<DomainOperationTransformer> transformers = context.getAttachment(OperationAttachments.SLAVE_SERVER_OPERATION_TRANSFORMERS);
        if (transformers == null) {
            context.attach(OperationAttachments.SLAVE_SERVER_OPERATION_TRANSFORMERS, transformers = new ArrayList<>());
        }
        final ModelNode slave = opBuilder.build().getOperation();
        transformers.add(new OverlayOperationTransformer(slave, context.getCurrentAddress()));
    }

    /**
     * Returns the deployment names with the specified runtime names at the specified deploymentRootAddress.
     *
     * @param deploymentRootResource
     * @param runtimeNames
     * @return the deployment names with the specified runtime names at the specified deploymentRootAddress.
     */
      public static Set<String> listDeployments(Resource deploymentRootResource, Set<String> runtimeNames) {
          return listDeploymentNames(deploymentRootResource, runtimeNames
                .stream()
                .map(wildcardExpr -> DeploymentOverlayIndex.getPattern(wildcardExpr))
                .collect(Collectors.toSet()));
      }

    private static Set<String> listDeploymentNames(Resource deploymentRootResource, Set<Pattern> patterns) {
        Set<String> deploymentNames = new HashSet<>();
        if (deploymentRootResource.hasChildren(DEPLOYMENT)) {
            for (Resource.ResourceEntry deploymentResource : deploymentRootResource.getChildren(DEPLOYMENT)) {
                if (isAcceptableDeployment(deploymentResource.getModel(), patterns)) {
                    deploymentNames.add(deploymentResource.getName());
                } else if (deploymentResource.hasChildren(SUBDEPLOYMENT)) {
                    for (Resource.ResourceEntry subdeploymentResource : deploymentResource.getChildren(SUBDEPLOYMENT)) {
                        if (isAcceptableDeployment(subdeploymentResource.getModel(), patterns)) {
                            deploymentNames.add(deploymentResource.getName());
                        }
                    }
                }
            }
        }
        return deploymentNames;
    }

    private static boolean isAcceptableDeployment(ModelNode deploymentNode, Set<Pattern> patterns) {
        return deploymentNode.isDefined() && deploymentNode.hasDefined(ENABLED) && deploymentNode.get(ENABLED).asBoolean()
                && patterns.stream().anyMatch(pattern -> pattern.matcher(deploymentNode.require(RUNTIME_NAME).asString()).matches());
    }

    private static ModelNode addRedeployStep(PathAddress address) {
        return Operations.createOperation(REDEPLOY, address.toModelNode());
    }

    private static Set<String> listServerGroupsReferencingOverlay(Resource rootResource, String overlayName) {
        final PathElement overlayPath = PathElement.pathElement(DEPLOYMENT_OVERLAY, overlayName);
        if (rootResource.hasChildren(SERVER_GROUP)) {
            return rootResource.getChildrenNames(SERVER_GROUP).stream().filter(
                    serverGroupName -> rootResource.getChild(PathElement.pathElement(SERVER_GROUP, serverGroupName)).hasChild(overlayPath)).collect(Collectors.toSet());
        }
        return Collections.emptySet();
    }

    private static final class OverlayOperationTransformer implements DomainOperationTransformer {

        private final ModelNode newOperation;
        private final PathAddress overlayAddress;

        public OverlayOperationTransformer(ModelNode newOperation, PathAddress overlayAddress) {
            this.newOperation = newOperation;
            this.overlayAddress = overlayAddress;
        }

        @Override
        public ModelNode transform(final OperationContext context, final ModelNode operation) {
            if (COMPOSITE.equals(operation.get(OP).asString())) {
                ModelNode ret = operation.clone();
                final List<ModelNode> list = new ArrayList<>();
                ListIterator<ModelNode> it = ret.get(STEPS).asList().listIterator();
                while (it.hasNext()) {
                    final ModelNode subOperation = it.next();
                    list.add(transform(context, subOperation));
                }
                ret.get(STEPS).set(list);
                ServerLogger.AS_ROOT_LOGGER.debugf("Transforming operation %s into %s", operation.toJSONString(true), ret.toJSONString(true));
                return ret;
            } else {
                if (matches(operation)) {
                    ServerLogger.AS_ROOT_LOGGER.debugf("Transforming operation %s into %s", operation.toJSONString(true), newOperation.toJSONString(true));
                    return newOperation.clone();
                } else {
                    return operation;
                }
            }
        }

        protected boolean matches(final ModelNode operation) {
            return (REDEPLOY_LINKS.equals(operation.get(OP).asString()) ||
                    (REMOVE.equals(operation.get(OP).asString()) && operation.hasDefined(REDEPLOY_AFFECTED) && operation.get(REDEPLOY_AFFECTED).asBoolean()))
                    && validOverlay(PathAddress.pathAddress(operation.get(OP_ADDR)));
        }

        private boolean validOverlay(PathAddress operationAddress) {
            if (operationAddress.size() > 1 && operationAddress.size() >= overlayAddress.size()) {
                ServerLogger.AS_ROOT_LOGGER.debugf("Comparing address %s with %s", operationAddress.subAddress(0, overlayAddress.size()).toCLIStyleString(), overlayAddress.toCLIStyleString());
                return operationAddress.subAddress(0, overlayAddress.size()).equals(overlayAddress);
            }
            return false;
        }
    }
}
