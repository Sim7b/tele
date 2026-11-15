"""Stand-in for dispatcharr/urls.py: API routes, then the catch-all of the web interface."""

from django.http import HttpResponse
from django.urls import path


def spa(request, unused_path=""):
    return HttpResponse("index")


urlpatterns = [
    path("api/other/", spa),
    path("", spa),
    path("<path:unused_path>", spa),
]
