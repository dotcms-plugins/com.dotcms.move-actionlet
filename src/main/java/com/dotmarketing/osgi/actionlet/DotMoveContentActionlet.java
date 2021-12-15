package com.dotmarketing.osgi.actionlet;

import com.dotcms.api.web.HttpServletRequestThreadLocal;
import com.dotcms.api.web.HttpServletResponseThreadLocal;
import com.dotcms.business.WrapInTransaction;
import com.dotcms.content.elasticsearch.business.ContentletIndexAPI;
import com.dotcms.content.elasticsearch.business.ContentletIndexAPIImpl;
import com.dotcms.mock.request.MockAttributeRequest;
import com.dotcms.mock.request.MockSessionRequest;
import com.dotcms.mock.response.BaseResponse;
import com.dotcms.rest.AnonymousAccess;
import com.dotmarketing.beans.Host;
import com.dotmarketing.beans.Identifier;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.CacheLocator;
import com.dotmarketing.business.PermissionAPI;
import com.dotmarketing.db.FlushCacheRunnable;
import com.dotmarketing.db.HibernateUtil;
import com.dotmarketing.exception.DoesNotExistException;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.exception.DotRuntimeException;
import com.dotmarketing.exception.DotSecurityException;
import com.dotmarketing.portlets.contentlet.business.HostAPI;
import com.dotmarketing.portlets.contentlet.model.Contentlet;
import com.dotmarketing.portlets.contentlet.model.ContentletVersionInfo;
import com.dotmarketing.portlets.folders.model.Folder;
import com.dotmarketing.portlets.workflows.actionlet.WorkFlowActionlet;
import com.dotmarketing.portlets.workflows.model.WorkflowActionClassParameter;
import com.dotmarketing.portlets.workflows.model.WorkflowActionFailureException;
import com.dotmarketing.portlets.workflows.model.WorkflowActionletParameter;
import com.dotmarketing.portlets.workflows.model.WorkflowProcessor;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.UtilMethods;
import com.dotmarketing.util.VelocityUtil;
import com.liferay.portal.model.User;
import com.liferay.util.StringPool;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.control.Try;
import org.apache.velocity.context.Context;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.dotmarketing.business.PermissionAPI.PERMISSION_CAN_ADD_CHILDREN;

/**
 * This Actionlet allows to the user to move a contentlet to another place
 * The user may set a path into the actionlet params, if the hosts exists but not the path it can be created.
 * @author jsanca
 */
public class DotMoveContentActionlet extends WorkFlowActionlet {

    /**
     * This is the parameter for the Actionlet
     */
    public static final String PATH_KEY = "path";

    public static final String HOST_INDICATOR     = "//";

    private final ContentletIndexAPI indexAPI = new ContentletIndexAPIImpl();

    @Override
    public List<WorkflowActionletParameter> getParameters() {
        List<WorkflowActionletParameter> params = new ArrayList<>();

        params.add(new WorkflowActionletParameter(PATH_KEY, "Optional path to move, for example: //demo.dotcms.com/application", "", true));

        return params;
    }

    @Override
    public String getName() {
        return "Dot Move";
    }

    @Override
    public String getHowTo() {
        return "If the path is not set on this actionlet, dotCMS will allow a user to select a destination";
    }

    @Override
    public void executeAction(final WorkflowProcessor processor,
                              final Map<String, WorkflowActionClassParameter> params) throws WorkflowActionFailureException {

        final Contentlet contentlet = processor.getContentlet();
        final User user             = processor.getUser();
        final String pathParam      = params.get(PATH_KEY).getValue();
        final String path           = this.evalVelocity(processor, pathParam);

        Logger.debug(this, "Moving the contentlet to: " + path);

        processor.setContentlet(Try.of(()->this.move(contentlet, user, path, false))
                .getOrElseThrow(e -> new WorkflowActionFailureException(e.getMessage(), (Exception) e)));
    }


    protected String evalVelocity(final WorkflowProcessor processor, final String velocityMessage) {

        final User currentUser = processor.getUser();
        final HttpServletRequest request =
                null == HttpServletRequestThreadLocal.INSTANCE.getRequest()?
                        this.mockRequest(currentUser): HttpServletRequestThreadLocal.INSTANCE.getRequest();
        final HttpServletResponse response =
                null == HttpServletResponseThreadLocal.INSTANCE.getResponse()?
                        this.mockResponse(): HttpServletResponseThreadLocal.INSTANCE.getResponse();

        final Context velocityContext = VelocityUtil.getInstance().getContext(request, response);
        velocityContext.put("workflow",   processor);
        velocityContext.put("user",       currentUser);
        velocityContext.put("contentlet", processor.getContentlet());
        velocityContext.put("content",    processor.getContentlet());

        try {
            return VelocityUtil.eval(velocityMessage, velocityContext);
        } catch (Exception e1) {
            Logger.warn(this.getClass(), "unable to parse message, falling back" + e1);
        }

        return velocityMessage;
    }

    protected HttpServletRequest  mockRequest (final User  currentUser) {

        final Host host = Try.of(()->APILocator.getHostAPI()
                .findDefaultHost(currentUser, false)).getOrElse(APILocator.systemHost());
        return new MockAttributeRequest(
                new MockSessionRequest(
                        new FakeHttpRequest(host.getHostname(), StringPool.FORWARD_SLASH).request()
                ).request()
        ).request();
    }

    protected HttpServletResponse mockResponse () {

        return new BaseResponse().response();
    }

    public Contentlet move(final Contentlet contentlet, final User user, final String hostAndFolderPath,
                           final boolean respectFrontendRoles) throws DotSecurityException, DotDataException {

        Logger.debug(this, ()->"Moving contentlet: " + contentlet.getIdentifier() + " to: " + hostAndFolderPath);

        if (UtilMethods.isNotSet(hostAndFolderPath) || !hostAndFolderPath.startsWith(HOST_INDICATOR)) {

            throw new IllegalArgumentException("The host path is not valid: " + hostAndFolderPath);
        }

        final Tuple2<String, Host> hostPathTuple = Try.of(()->splitPathHost(hostAndFolderPath, user,
                StringPool.FORWARD_SLASH)).getOrElseThrow(e -> new DotRuntimeException(e));

        return this.move(contentlet, user, hostPathTuple._2(), hostPathTuple._1(), respectFrontendRoles);
    }

    /**
     * Based on a path tries to split the host and the relative path. If the path is already relative, gets the host from the resourceHost or gets the default one
     * @param inputPath {@link String} relative or absolute path
     * @param user {@link User} user
     * @param posHostToken {@link String} is the token that become immediate to the host, for instance if getting the host from a container postHostToken could be "/application/containers"
     * @return Tuple2 path and host
     * @throws DotSecurityException
     * @throws DotDataException
     */
    public static Tuple2<String, Host> splitPathHost(final String inputPath, final User user, final String posHostToken) throws DotSecurityException, DotDataException {

        final HostAPI hostAPI        = APILocator.getHostAPI();
        final int hostIndicatorIndex = inputPath.indexOf(HOST_INDICATOR);
        final boolean hasHost        = hostIndicatorIndex != -1;
        final int     hostIndicatorLength = HOST_INDICATOR.length();
        final int applicationContainerFolderStartsIndex = hasHost?
                inputPath.substring(hostIndicatorLength).indexOf(posHostToken)+hostIndicatorLength:inputPath.indexOf(posHostToken);
        final boolean hasPos         = applicationContainerFolderStartsIndex != -1;
        final String hostName        = hasHost && hasPos?
                inputPath.substring(hostIndicatorIndex+2, applicationContainerFolderStartsIndex):null;
        final String path            = hasHost && hasPos?inputPath.substring(applicationContainerFolderStartsIndex):inputPath;
        final Host host 	         = hasHost?hostAPI.findByName(hostName, user, false):null;

        return Tuple.of(path, host);
    }

    public Contentlet move(final Contentlet contentlet, final User user, final Host host, final String folderPathParam,
                           final boolean respectFrontendRoles) throws DotSecurityException, DotDataException {

        Logger.debug(this, ()->"Moving contentlet: " + contentlet.getIdentifier() + " to: " + folderPathParam);

        if (UtilMethods.isNotSet(folderPathParam) || !folderPathParam.startsWith(StringPool.SLASH)) {

            throw new IllegalArgumentException("The folder is not valid: " + folderPathParam);
        }

        // we need a / at the end to check if exits
        final String folderPath = folderPathParam.endsWith(StringPool.SLASH)?folderPathParam: folderPathParam + StringPool.SLASH;

        //Check if the folder exists via Admin user, b/c user couldn't have VIEW Permissions over the folder
        final Folder folder = Try.of(()-> APILocator.getFolderAPI()
                .findFolderByPath(folderPath, host, APILocator.systemUser(), respectFrontendRoles)).getOrNull();

        if (null == folder || !UtilMethods.isSet(folder.getInode())) {
            throw new IllegalArgumentException("The folder does not exists: " + folderPath + " and could not be created");
        }

        return this.move(contentlet, user, host, folder, respectFrontendRoles);
    }

    private void throwSecurityException(final Contentlet contentlet,
                                        final User user) throws DotSecurityException {

        final String userName = (user != null ? user.getUserId() : "Unknown");
        final String message  = UtilMethods.isSet(contentlet.getIdentifier())?
                "User: " + userName +" doesn't have write permissions to Contentlet: " + contentlet.getIdentifier():
                "User: " + userName +" doesn't have write permissions to create the Contentlet";

        throw new DotSecurityException(message);
    }

    @WrapInTransaction
    public Contentlet move(final Contentlet contentlet, final User incomingUser, final Host host, final Folder folder,
                           final boolean respectFrontendRoles) throws DotSecurityException, DotDataException {

        Logger.debug(this, ()-> "Moving contentlet: " + contentlet.getIdentifier()
                + " to host: " + host.getHostname() + " and path: " + folder.getPath() + ", id: " + folder.getIdentifier());

        final User user = incomingUser!=null ? incomingUser: APILocator.getUserAPI().getAnonymousUser();

        if(user.isAnonymousUser() && AnonymousAccess.systemSetting() != AnonymousAccess.WRITE) {
            throw new DotSecurityException("CONTENT_APIS_ALLOW_ANONYMOUS setting does not allow anonymous content WRITEs");
        }

        // if the user can write and add a children to the folder
        if (!APILocator.getPermissionAPI().doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_WRITE, user, respectFrontendRoles) ||
                !APILocator.getPermissionAPI().doesUserHavePermission(folder, PERMISSION_CAN_ADD_CHILDREN, user)) {

            this.throwSecurityException(contentlet, user);
        }

        final Identifier identifier = APILocator.getIdentifierAPI().loadFromDb(contentlet.getIdentifier());

        // if id exists
        if (null == identifier || !UtilMethods.isSet(identifier.getId())) {

            throw new DoesNotExistException("The identifier does not exists: " + contentlet.getIdentifier());
        }

        //Check if another content with the same name already exists in the new folder
        if(APILocator.getFileAssetAPI().fileNameExists(host, folder, identifier.getAssetName(), contentlet.getIdentifier())){
            throw new IllegalArgumentException("Content with the same name: '" + identifier.getAssetName() + "' already exists at the new path: " + host.getHostname() + folder.getPath());
        }

        if (contentlet.isVanityUrl()) {

            contentlet.setProperty("site", host.getIdentifier());
        }
        // update with the new host and path
        identifier.setHostId(host.getIdentifier());
        identifier.setParentPath(folder.getPath());

        Logger.debug(this, ()->"Updating the identifier: " + identifier);
        // changing the host and path will move the contentlet
        APILocator.getIdentifierAPI().save(identifier);

        // update the version ts in order to be repushed
        final ContentletVersionInfo versionInfoOpt = APILocator.getVersionableAPI()
                .getContentletVersionInfo(identifier.getId(), contentlet.getLanguageId());
        if (null != versionInfoOpt) {

            versionInfoOpt.setVersionTs(new Date());
            APILocator.getVersionableAPI().saveContentletVersionInfo(versionInfoOpt);
        }

        // update the content host + folder
        contentlet.setHost(host.getIdentifier());
        contentlet.setFolder(folder.getInode());

        // clean cache
        HibernateUtil.addCommitListener(identifier.getId(), new FlushCacheRunnable() {
            @Override
            public void run() {
                CacheLocator.getContentletCache().remove(contentlet.getInode());
            }
        });

        // refresh the index based on the index policy
        this.indexAPI.addContentToIndex(contentlet, false);

        return contentlet;
    }

}
