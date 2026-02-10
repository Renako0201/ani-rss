package ani.rss.action;

import ani.rss.task.RssTask;
import ani.rss.web.action.BaseAction;
import ani.rss.web.annotation.Auth;
import ani.rss.web.annotation.Path;
import cn.hutool.http.server.HttpServerRequest;
import cn.hutool.http.server.HttpServerResponse;

@Auth
@Path("/rssTaskStatus")
public class RssTaskStatusAction implements BaseAction {
    @Override
    public void doAction(HttpServerRequest request, HttpServerResponse response) {
        resultSuccess(RssTask.getStatus());
    }
}

