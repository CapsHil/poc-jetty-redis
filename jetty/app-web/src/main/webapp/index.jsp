<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN"
"http://www.w3.org/TR/html4/loose.dtd">
<html>
    <head>
        <title>web-1</title>
    </head>
    <body>
        <div>
            Server host name is: <b><%=System.getenv("HOSTNAME")%></b>
        </div>
        <div>
            Session id is: <b><%= session.getId() %></b>
        </div>
    </body>
</html>
