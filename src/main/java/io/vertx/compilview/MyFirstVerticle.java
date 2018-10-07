package io.vertx.compilview;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.asyncsql.MySQLClient;
import io.vertx.ext.bridge.BridgeEventType;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.SQLClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.UpdateResult;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
//import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import java.util.ArrayList;
import java.util.List;

/**
 * This is a verticle. A verticle is a _Vert.x component_. This verticle is implemented in Java, but you can
 * implement them in JavaScript, Groovy or even Ruby.
 */
public class MyFirstVerticle extends AbstractVerticle {

  private final Logger logger = LoggerFactory.getLogger(MyFirstVerticle.class);
  
  private JDBCClient jdbc;
  
  private SQLClient urconti01SQLClient;
  
  private SQLClient urconti02SQLClient;

  private SockJSHandler sockJSHandler;
  
  private EventBus eventBus;

  /**
   * This method is called when the verticle is deployed. It creates a HTTP server and registers a simple request
   * handler.
   * <p/>
   * Notice the `listen` method. It passes a lambda checking the port binding result. When the HTTP server has been
   * bound on the port, it call the `complete` method to inform that the starting has completed. Else it reports the
   * error.
   *
   * @param fut the future
   */
  @Override
  public void start(Future<Void> fut) {

    // Create a JDBC client
    jdbc = JDBCClient.createShared(vertx, config(), "My-Whisky-Collection");

    eventBus = vertx.eventBus();
    
    sockJSHandler = SockJSHandler.create(vertx);

    /*PermittedOptions permittedOptions = new PermittedOptions().setAddressRegex("compilview\\..+");
    BridgeOptions bridgeOptions = new BridgeOptions()
      .addInboundPermitted(permittedOptions)
      .addOutboundPermitted(permittedOptions);
    */
    BridgeOptions options = new BridgeOptions()
    	      .addInboundPermitted(new PermittedOptions().setAddress("compilview.urconti01"))
    	      .addOutboundPermitted(new PermittedOptions().setAddress("compilview.urconti01"))
    	      .addOutboundPermitted(new PermittedOptions().setAddress("events.log"));
    
    sockJSHandler.bridge(options, event -> {
      if (BridgeEventType.SOCKET_CREATED.equals(event.type())) {
        logger.info("New client: " + event.socket().remoteAddress());
      }
      event.complete(true);
    });
    
    JsonObject urconti01SQLClientConfig = new JsonObject()
    		.put("host", "urconti01.cylande.com")
    		.put("port", 3306)
    		.put("database", "continuum")
    		.put("username", "continuum")
    		.put("password", "continuum");
    urconti01SQLClient = MySQLClient.createShared(vertx, urconti01SQLClientConfig);
    
    JsonObject urconti02SQLClientConfig = new JsonObject()
    		.put("host", "urconti02.cylande.com")
    		.put("port", 3306)
    		.put("database", "continuum")
    		.put("username", "continuum")
    		.put("password", "continuum");
    urconti02SQLClient = MySQLClient.createShared(vertx, urconti02SQLClientConfig);
    
    startBackend(
          (nothing) -> startWebApp(
              (http) -> completeStartup(http, fut)
          ), fut);
  }

  private void startBackend(Handler<AsyncResult<SQLConnection>> next, Future<Void> fut) {
    jdbc.getConnection(ar -> {
      if (ar.failed()) {
        fut.fail(ar.cause());
      } else {
        next.handle(Future.succeededFuture(ar.result()));
      }
    });
  }

  private void startWebApp(Handler<AsyncResult<HttpServer>> next) {
    // Create a router object.
    Router router = Router.router(vertx);

    // Bind "/" to our hello message.
    router.route("/").handler(routingContext -> {
      HttpServerResponse response = routingContext.response();
      response
          .putHeader("content-type", "text/html")
          .end("<h1>Hello from my first Vert.x 3 application</h1>");
    });

    router.route("/eventbus/*").handler(sockJSHandler);
    
    router.route("/assets/*").handler(StaticHandler.create("assets"));

    router.get("/api/whiskies").handler(this::getAll);
    router.route("/api/whiskies*").handler(BodyHandler.create());
    router.post("/api/whiskies").handler(this::addOne);
    router.get("/api/whiskies/:id").handler(this::getOne);
    router.put("/api/whiskies/:id").handler(this::updateOne);
    router.delete("/api/whiskies/:id").handler(this::deleteOne);

    vertx.setPeriodic(5000, this::refresh);

    // Create the HTTP server and pass the "accept" method to the request handler.
    vertx
        .createHttpServer()
        .requestHandler(router::accept)
        .listen(
            // Retrieve the port from the configuration,
            // default to 8080.
            config().getInteger("http.port", 8080),
            next::handle
        );
  }

  private void completeStartup(AsyncResult<HttpServer> http, Future<Void> fut) {
    if (http.succeeded()) {
      fut.complete();
    } else {
      fut.fail(http.cause());
    }
  }


  @Override
  public void stop() throws Exception {
    // Close the JDBC client.
    jdbc.close();
    urconti01SQLClient.close();
  }

  private void addOne(RoutingContext routingContext) {
    jdbc.getConnection(ar -> {
      // Read the request's content and create an instance of Whisky.
      final Whisky whisky = Json.decodeValue(routingContext.getBodyAsString(),
          Whisky.class);
      SQLConnection connection = ar.result();
      insert(whisky, connection, (r) ->
          routingContext.response()
              .setStatusCode(201)
              .putHeader("content-type", "application/json; charset=utf-8")
              .end(Json.encodePrettily(r.result())));
          connection.close();
    });

  }

  private void getOne(RoutingContext routingContext) {
    final String id = routingContext.request().getParam("id");
    if (id == null) {
      routingContext.response().setStatusCode(400).end();
    } else {
      jdbc.getConnection(ar -> {
        // Read the request's content and create an instance of Whisky.
        SQLConnection connection = ar.result();
        select(id, connection, result -> {
          if (result.succeeded()) {
            routingContext.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .end(Json.encodePrettily(result.result()));
          } else {
            routingContext.response()
                .setStatusCode(404).end();
          }
          connection.close();
        });
      });
    }
  }

  private void updateOne(RoutingContext routingContext) {
    final String id = routingContext.request().getParam("id");
    JsonObject json = routingContext.getBodyAsJson();
    if (id == null || json == null) {
      routingContext.response().setStatusCode(400).end();
    } else {
      jdbc.getConnection(ar ->
          update(id, json, ar.result(), (whisky) -> {
            if (whisky.failed()) {
              routingContext.response().setStatusCode(404).end();
            } else {
              routingContext.response()
                  .putHeader("content-type", "application/json; charset=utf-8")
                  .end(Json.encodePrettily(whisky.result()));
            }
            ar.result().close();
          })
      );
    }
  }

  private void deleteOne(RoutingContext routingContext) {
    String id = routingContext.request().getParam("id");
    if (id == null) {
      routingContext.response().setStatusCode(400).end();
    } else {
      jdbc.getConnection(ar -> {
        SQLConnection connection = ar.result();
        connection.execute("DELETE FROM Whisky WHERE id='" + id + "'",
            result -> {
              routingContext.response().setStatusCode(204).end();
              connection.close();
            });
      });
    }
  }

  private void refresh(Long routingContext) {
	urconti01SQLClient.query("SELECT * FROM PROJECTGROUP", result -> {
      List<Whisky> whiskies = new ArrayList<>();
  	  for (JsonObject jsonObject : result.result().getRows())
  	  {
  		whiskies.add(new Whisky(jsonObject.getString("GROUP_ID"), jsonObject.getString("DESCRIPTION")));
  	  }
  	  eventBus.publish("compilview.urconti01", Json.encodePrettily(whiskies));
    });
	/*jdbc.getConnection(ar -> {
      SQLConnection connection = ar.result();
      connection.query("SELECT * FROM Whisky", result -> {
        List<Whisky> whiskies = result.result().getRows().stream().map(Whisky::new).collect(Collectors.toList());
        routingContext.response()
            .putHeader("content-type", "application/json; charset=utf-8")
            .end(Json.encodePrettily(whiskies));
        connection.close();
      });
    });*/
  }
  
  private void getAll(RoutingContext routingContext) {
	urconti01SQLClient.query("SELECT * FROM PROJECTGROUP", result -> {
      List<Whisky> whiskies = new ArrayList<>();
  	  for (JsonObject jsonObject : result.result().getRows())
  	  {
  		whiskies.add(new Whisky(jsonObject.getString("GROUP_ID"), jsonObject.getString("DESCRIPTION")));
  	  }
  	  routingContext.response()
            .putHeader("content-type", "application/json; charset=utf-8")
            .end(Json.encodePrettily(whiskies));
      });
	/*jdbc.getConnection(ar -> {
      SQLConnection connection = ar.result();
      connection.query("SELECT * FROM Whisky", result -> {
        List<Whisky> whiskies = result.result().getRows().stream().map(Whisky::new).collect(Collectors.toList());
        routingContext.response()
            .putHeader("content-type", "application/json; charset=utf-8")
            .end(Json.encodePrettily(whiskies));
        connection.close();
      });
    });*/
  }

  /*private void createSomeData(AsyncResult<SQLConnection> result, Handler<AsyncResult<Void>> next, Future<Void> fut) {
    if (result.failed()) {
      fut.fail(result.cause());
    } else {
      SQLConnection connection = result.result();
      connection.execute(
          "CREATE TABLE IF NOT EXISTS Whisky (id INTEGER IDENTITY, name varchar(100), origin varchar" +
              "(100))",
          ar -> {
            if (ar.failed()) {
              fut.fail(ar.cause());
              connection.close();
              return;
            }
            connection.query("SELECT * FROM Whisky", select -> {
              if (select.failed()) {
                fut.fail(select.cause());
                connection.close();
                return;
              }
              if (select.result().getNumRows() == 0) {
                insert(
                    new Whisky("Bowmore 15 Years Laimrig", "Scotland, Islay"), connection,
                    (v) -> insert(new Whisky("Talisker 57° North", "Scotland, Island"), connection,
                        (r) -> {
                          next.handle(Future.<Void>succeededFuture());
                          connection.close();
                        }));
              } else {
                next.handle(Future.<Void>succeededFuture());
                connection.close();
              }
            });

          });
    }
  }*/

  private void insert(Whisky whisky, SQLConnection connection, Handler<AsyncResult<Whisky>> next) {
    String sql = "INSERT INTO Whisky (name, origin) VALUES ?, ?";
    connection.updateWithParams(sql,
        new JsonArray().add(whisky.getName()).add(whisky.getOrigin()),
        (ar) -> {
          if (ar.failed()) {
            next.handle(Future.failedFuture(ar.cause()));
            connection.close();
            return;
          }
          UpdateResult result = ar.result();
          // Build a new whisky instance with the generated id.
          Whisky w = new Whisky(result.getKeys().getInteger(0), whisky.getName(), whisky.getOrigin());
          next.handle(Future.succeededFuture(w));
        });
  }

  private void select(String id, SQLConnection connection, Handler<AsyncResult<Whisky>> resultHandler) {
    connection.queryWithParams("SELECT * FROM Whisky WHERE id=?", new JsonArray().add(id), ar -> {
      if (ar.failed()) {
        resultHandler.handle(Future.failedFuture("Whisky not found"));
      } else {
        if (ar.result().getNumRows() >= 1) {
          resultHandler.handle(Future.succeededFuture(new Whisky(ar.result().getRows().get(0))));
        } else {
          resultHandler.handle(Future.failedFuture("Whisky not found"));
        }
      }
    });
  }

  private void update(String id, JsonObject content, SQLConnection connection,
                      Handler<AsyncResult<Whisky>> resultHandler) {
    String sql = "UPDATE Whisky SET name=?, origin=? WHERE id=?";
    connection.updateWithParams(sql,
        new JsonArray().add(content.getString("name")).add(content.getString("origin")).add(id),
        update -> {
          if (update.failed()) {
            resultHandler.handle(Future.failedFuture("Cannot update the whisky"));
            return;
          }
          if (update.result().getUpdated() == 0) {
            resultHandler.handle(Future.failedFuture("Whisky not found"));
            return;
          }
          resultHandler.handle(
              Future.succeededFuture(new Whisky(Integer.valueOf(id),
                  content.getString("name"), content.getString("origin"))));
        });
  }

}
