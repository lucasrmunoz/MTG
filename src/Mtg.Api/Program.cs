using Mtg.Api;
using Mtg.Api.Endpoints;
using Mtg.Api.Sessions;
using Mtg.Core;

const string CorsPolicy = "Frontend";

var builder = WebApplication.CreateBuilder(args);

var allowedOrigins = builder.Configuration.GetSection("Cors:AllowedOrigins").Get<string[]>()
                     ?? ["http://localhost:3000"];

builder.Services.AddScryfall();
builder.Services.AddPricing();
builder.Services.AddSingleton<GameSessionRegistry>();
builder.Services.AddHostedService<SessionCleanupService>();
builder.Services.AddOpenApi();
builder.Services.AddProblemDetails();
builder.Services.AddExceptionHandler<ScryfallExceptionHandler>();
builder.Services.AddCors(options =>
{
    options.AddPolicy(CorsPolicy, policy => policy
        .WithOrigins(allowedOrigins)
        .AllowAnyHeader()
        .AllowAnyMethod());
});

var app = builder.Build();

app.UseCors(CorsPolicy);
app.UseExceptionHandler();
app.UseStatusCodePages();

if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();
}

app.UseWebSockets();

app.MapCardEndpoints();
app.MapSessionEndpoints();

app.Run();
